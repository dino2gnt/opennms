/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2020 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2020 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.telemetry.protocols.bmp.adapter;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.collection.api.AttributeType;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionAgentFactory;
import org.opennms.netmgt.collection.support.builder.CollectionSetBuilder;
import org.opennms.netmgt.collection.support.builder.DeferredGenericTypeResource;
import org.opennms.netmgt.collection.support.builder.NodeLevelResource;
import org.opennms.netmgt.dao.api.InterfaceToNodeCache;
import org.opennms.netmgt.dao.api.SessionUtils;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.Message;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.Type;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.BaseAttribute;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Collector;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Peer;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Router;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Stat;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.UnicastPrefix;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpBaseAttribute;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpBaseAttributeDao;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpCollector;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpCollectorDao;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpGlobalIpRib;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpGlobalIpRibDao;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpPeer;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpPeerDao;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpRouter;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpRouterDao;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpStatReports;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpUnicastPrefix;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpUnicastPrefixDao;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.PrefixByAS;
import org.opennms.netmgt.telemetry.protocols.collection.CollectionSetWithAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class BmpMessagePersister implements BmpPersistenceMessageHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BmpMessagePersister.class);

    @Autowired
    private BmpCollectorDao bmpCollectorDao;

    @Autowired
    private BmpRouterDao bmpRouterDao;

    @Autowired
    private BmpPeerDao bmpPeerDao;

    @Autowired
    private BmpBaseAttributeDao bmpBaseAttributeDao;

    @Autowired
    private BmpUnicastPrefixDao bmpUnicastPrefixDao;

    @Autowired
    private BmpGlobalIpRibDao bmpGlobalIpRibDao;

    @Autowired
    private SessionUtils sessionUtils;

    private CollectionAgentFactory collectionAgentFactory;

    @Autowired
    private InterfaceToNodeCache interfaceToNodeCache;

    private final ThreadFactory threadFactory = new ThreadFactoryBuilder()
            .setNameFormat("updateGlobalRibs-%d")
            .build();
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
            threadFactory);


    private Map<String, Long> updatesByPeer = new ConcurrentHashMap<>();
    private Map<String, Long> withdrawsByPeer = new ConcurrentHashMap<>();
    private Map<AsnKey, Long> updatesByAsn = new ConcurrentHashMap<>();
    private Map<AsnKey, Long> withdrawsByAsn = new ConcurrentHashMap<>();
    private Map<PrefixKey, Long> updatesByPrefix = new ConcurrentHashMap<>();
    private Map<PrefixKey, Long> withdrawsByPrefix = new ConcurrentHashMap<>();
    private ConcurrentLinkedQueue<CollectionSetWithAgent> collectionSetQueue = new ConcurrentLinkedQueue<>();


    @Override
    public void handle(Message message, String location) {
        sessionUtils.withTransaction(() -> {
            switch (message.getType()) {
                case COLLECTOR:
                    List<BmpCollector> bmpCollectors = buildBmpCollectors(message);
                    // Update routers state to down when collector is just starting or going into stopped state.
                    bmpCollectors.forEach(collector -> {
                        if (collector.getAction().equals(Collector.Action.STARTED.value) ||
                                collector.getAction().equals(Collector.Action.STOPPED.value)) {
                            collector.getBmpRouters().forEach(bmpRouter -> {
                                // Set down state for routers.
                                bmpRouter.setState(false);
                            });
                        }
                        try {
                            bmpCollectorDao.saveOrUpdate(collector);
                        } catch (Exception e) {
                            LOG.error("Exception while persisting BMP collector {}", collector, e);
                        }
                    });
                    break;
                case ROUTER:
                    BmpCollector bmpCollector = bmpCollectorDao.findByCollectorHashId(message.getCollectorHashId());
                    if (bmpCollector != null) {
                        List<BmpRouter> bmpRouters = buildBmpRouters(message, bmpCollector);
                        bmpRouters.forEach(router -> {
                            Integer connections = router.getConnectionCount();
                            // Upon initial router message in INIT/FIRST state,  update all corresponding peer state to down.
                            boolean state = !router.getAction().equals(Router.Action.TERM.value);
                            if (connections == 0 && state) {
                                router.getBmpPeers().forEach(bmpPeer -> {
                                    if (bmpPeer.getTimestamp().getTime() < router.getTimestamp().getTime()) {
                                        bmpPeer.setState(false);
                                    }
                                });
                            }
                            Integer count = state ? ++connections : --connections;
                            router.setConnectionCount(count);
                            try {
                                bmpRouterDao.saveOrUpdate(router);
                            } catch (Exception e) {
                                LOG.error("Exception while persisting BMP router {}", router, e);
                            }

                        });
                    }
                    break;
                case PEER:
                    List<BmpPeer> bmpPeers = buildBmpPeers(message);
                    // Only retain unicast prefixes that are updated after current peer UP/down message.
                    bmpPeers.forEach(peer -> {
                        Set<BmpUnicastPrefix> unicastPrefixes = peer.getBmpUnicastPrefixes().stream().filter(bmpUnicastPrefix ->
                                bmpUnicastPrefix.getTimestamp().getTime() > peer.getTimestamp().getTime()
                        ).collect(Collectors.toSet());
                        peer.setBmpUnicastPrefixes(unicastPrefixes);
                        try {
                            bmpPeerDao.saveOrUpdate(peer);
                        } catch (Exception e) {
                            LOG.error("Exception while persisting BMP peer {}", peer, e);
                        }
                    });
                    break;
                case BASE_ATTRIBUTE:
                    List<BmpBaseAttribute> bmpBaseAttributes = buildBmpBaseAttributes(message);
                    bmpBaseAttributes.forEach(bmpBaseAttribute -> {
                        try {
                            bmpBaseAttributeDao.saveOrUpdate(bmpBaseAttribute);
                        } catch (Exception e) {
                            LOG.error("Exception while persisting BMP base attribute {}", bmpBaseAttribute, e);
                        }
                    });
                    break;
                case UNICAST_PREFIX:
                    List<BmpUnicastPrefix> bmpUnicastPrefixes = buildBmpUnicastPrefix(message);
                    bmpUnicastPrefixes.forEach(unicastPrefix -> {
                        try {
                            updateStats(unicastPrefix, location);
                            bmpUnicastPrefixDao.saveOrUpdate(unicastPrefix);
                        } catch (Exception e) {
                            LOG.error("Exception while persisting BMP unicast prefix {}", unicastPrefix, e);
                        }
                    });
                    break;
            }
        });
    }

    public void init() {
        scheduledExecutorService.scheduleAtFixedRate(this::updateGlobalRibs, 0, 5, TimeUnit.MINUTES);
    }

    void updateGlobalRibs() {
        List<PrefixByAS> prefixByASList = bmpUnicastPrefixDao.getPrefixesGroupedbyAS();
        prefixByASList.forEach(prefixByAS -> {
            BmpGlobalIpRib bmpGlobalIpRib = buildGlobalIpRib(prefixByAS);
            if (bmpGlobalIpRib != null) {
                try {
                    bmpGlobalIpRibDao.saveOrUpdate(bmpGlobalIpRib);
                } catch (Exception e) {
                    LOG.error("Exception while persisting BMP global iprib  {}", bmpGlobalIpRib, e);
                }
            }
        });
    }

    private void updateStats(BmpUnicastPrefix unicastPrefix, String location) {
        // Update counts if this is new prefix update or
        // if previous withdrawn state is different or it's an update with different base attributes
        if (unicastPrefix.getId() == null ||
                (unicastPrefix.isWithDrawn() != unicastPrefix.isPrevWithDrawnState() ||
                        (!unicastPrefix.isWithDrawn() && !unicastPrefix.getBaseAttrHashId().equals(unicastPrefix.getPrevBaseAttrHashId())))) {

            String peerHashId = unicastPrefix.getBmpPeer().getHashId();
            Long originAsn = unicastPrefix.getOriginAs();
            String prefix = unicastPrefix.getPrefix();
            Integer prefixLen = unicastPrefix.getPrefixLen();
            boolean isWithdrawn = unicastPrefix.isWithDrawn();
            if (isWithdrawn) {
                withdrawsByPeer.compute(peerHashId, (hashId, value) -> (value == null) ? 1 : value + 1);
                withdrawsByAsn.compute(new AsnKey(peerHashId, originAsn), (hashId, value) -> (value == null) ? 1 : value + 1);
                withdrawsByPrefix.compute(new PrefixKey(peerHashId, prefix, prefixLen), (hashId, value) -> (value == null) ? 1 : value + 1);
            } else {
                updatesByPeer.compute(peerHashId, (hashId, value) -> (value == null) ? 1 : value + 1);
                updatesByAsn.compute(new AsnKey(peerHashId, originAsn), (hashId, value) -> (value == null) ? 1 : value + 1);
                updatesByPrefix.compute(new PrefixKey(peerHashId, prefix, prefixLen), (hashId, value) -> (value == null) ? 1 : value + 1);
            }


            // Find the node for the router who has exported the stats and build a collection agent for it
            String routerAddr = unicastPrefix.getBmpPeer().getBmpRouter().getIpAddress();
            String peerAddr = unicastPrefix.getBmpPeer().getPeerAddr();
            InetAddress sourceAddr = InetAddressUtils.getInetAddress(routerAddr);
            Optional<Integer> nodeId = this.interfaceToNodeCache.getFirstNodeId(location, sourceAddr);
            if (!nodeId.isPresent()) {
                return;
            }
            final CollectionAgent agent = this.collectionAgentFactory.createCollectionAgent(Integer.toString(nodeId.get()), sourceAddr);
            // Build resource for the peer
            final NodeLevelResource nodeResource = new NodeLevelResource(agent.getNodeId());
            final DeferredGenericTypeResource peerResource = new DeferredGenericTypeResource(nodeResource, "bmp", peerAddr);

            // Build the collection set for the peer
            final CollectionSetBuilder builder = new CollectionSetBuilder(agent);
            builder.withTimestamp(unicastPrefix.getTimestamp());

            final String peerUpdates = String.format("bmp_%s_%s", peerAddr, "updates_by_peer");
            builder.withIdentifiedNumericAttribute(peerResource, "bmp", "updates_by_peer", updatesByPeer.get(peerHashId),
                    AttributeType.COUNTER, peerUpdates);
            final String peerWithdraws = String.format("bmp_%s_%s", peerAddr, "withdraws_by_peer");
            builder.withIdentifiedNumericAttribute(peerResource, "bmp", "withdraws_by_peer", withdrawsByPeer.get(peerHashId),
                    AttributeType.COUNTER, peerWithdraws);

            final String asnUpdates = String.format("bmp_%s_%s", peerAddr, "updates_by_asn");
            builder.withIdentifiedNumericAttribute(peerResource, "bmp", "updates_by_asn", updatesByAsn.get(new AsnKey(peerHashId, originAsn)),
                    AttributeType.COUNTER, asnUpdates);
            final String asnWithdraws = String.format("bmp_%s_%s", peerAddr, "withdraws_by_asn");
            builder.withIdentifiedNumericAttribute(peerResource, "bmp", "withdraws_by_asn", withdrawsByAsn.get(new AsnKey(peerHashId, originAsn)),
                    AttributeType.COUNTER, asnWithdraws);

            final String prefixUpdates = String.format("bmp_%s_%s", peerAddr, "updates_by_prefix");
            builder.withIdentifiedNumericAttribute(peerResource, "bmp", "updates_by_prefix", updatesByPrefix.get(new PrefixKey(peerHashId, prefix, prefixLen)),
                    AttributeType.COUNTER, prefixUpdates);

            final String prefixWithdraws = String.format("bmp_%s_%s", peerAddr, "withdraws_by_prefix");
            builder.withIdentifiedNumericAttribute(peerResource, "bmp", "withdraws_by_prefix", withdrawsByPrefix.get(new PrefixKey(peerHashId, prefix, prefixLen)),
                    AttributeType.COUNTER, prefixWithdraws);

            CollectionSetWithAgent collectionSetWithAgent = new CollectionSetWithAgent(agent, builder.build());
            collectionSetQueue.add(collectionSetWithAgent);
        }

    }


    @Override
    public void close() {
        scheduledExecutorService.shutdown();
    }

    private List<BmpCollector> buildBmpCollectors(Message message) {

        List<BmpCollector> bmpCollectors = new ArrayList<>();
        message.getRecords().forEach(record -> {
            if (record.getType().equals(Type.COLLECTOR)) {
                Collector collector = (Collector) record;
                try {
                    BmpCollector collectorEntity = bmpCollectorDao.findByCollectorHashId(collector.hash);
                    if (collectorEntity == null) {
                        collectorEntity = new BmpCollector();
                    }
                    collectorEntity.setAction(collector.action.value);
                    collectorEntity.setAdminId(collector.adminId);
                    collectorEntity.setHashId(collector.hash);
                    String routers = collector.routers != null ? Joiner.on(',').join(Iterables.transform(collector.routers, InetAddressUtils::str)) : "";
                    collectorEntity.setRouters(routers);
                    int routerCount = collector.routers != null ? collector.routers.size() : 0;
                    collectorEntity.setRoutersCount(routerCount);
                    // Boolean to represent Up/Down, Any state other than stopped is Up
                    boolean state = !(collector.action.equals(Collector.Action.STOPPED));
                    collectorEntity.setState(state);
                    collectorEntity.setTimestamp(Date.from(collector.timestamp));
                    bmpCollectors.add(collectorEntity);
                } catch (Exception e) {
                    LOG.error("Exception while mapping collector with admin Id {} to Collector entity", collector.adminId, e);
                }
            }
        });
        return bmpCollectors;
    }

    private List<BmpRouter> buildBmpRouters(Message message, BmpCollector bmpCollector) {
        List<BmpRouter> bmpRouters = new ArrayList<>();
        message.getRecords().forEach(record -> {
            if (record.getType().equals(Type.ROUTER)) {
                Router router = (Router) record;
                try {
                    BmpRouter bmpRouterEntity = bmpRouterDao.findByRouterHashId(router.hash);
                    if (bmpRouterEntity == null) {
                        bmpRouterEntity = new BmpRouter();
                    }
                    bmpRouterEntity.setHashId(router.hash);
                    bmpRouterEntity.setName(router.name);
                    bmpRouterEntity.setIpAddress(InetAddressUtils.str(router.ipAddress));
                    bmpRouterEntity.setTimestamp(Date.from(router.timestamp));
                    bmpRouterEntity.setTermReasonText(router.termReason);
                    bmpRouterEntity.setTermReasonCode(router.termCode);
                    bmpRouterEntity.setTermData(router.termData);
                    bmpRouterEntity.setBgpId(InetAddressUtils.str(router.bgpId));
                    bmpRouterEntity.setDescription(router.description);
                    bmpRouterEntity.setInitData(router.initData);
                    boolean state = !(router.action.equals(Router.Action.TERM));
                    bmpRouterEntity.setAction(router.action.value);
                    bmpRouterEntity.setState(state);
                    bmpRouterEntity.setBmpCollector(bmpCollector);
                    bmpRouters.add(bmpRouterEntity);
                } catch (Exception e) {
                    LOG.error("Exception while mapping Router with IpAddress '{}' to Router entity", InetAddressUtils.str(router.ipAddress), e);
                }
            }
        });
        return bmpRouters;
    }


    private List<BmpPeer> buildBmpPeers(Message message) {

        List<BmpPeer> bmpPeers = new ArrayList<>();
        message.getRecords().forEach(record -> {
            if (record.getType().equals(Type.PEER)) {
                Peer peer = (Peer) record;
                try {
                    BmpRouter bmpRouter;
                    BmpPeer peerEntity = bmpPeerDao.findByPeerHashId(peer.hash);
                    if (peerEntity == null) {
                        peerEntity = new BmpPeer();
                        bmpRouter = bmpRouterDao.findByRouterHashId(peer.routerHash);
                    } else {
                        bmpRouter = peerEntity.getBmpRouter();
                    }
                    peerEntity.setBmpRouter(bmpRouter);
                    peerEntity.setHashId(peer.hash);
                    peerEntity.setPeerRd(peer.peerRd);
                    peerEntity.setIpv4(peer.ipv4);
                    peerEntity.setPeerAddr(InetAddressUtils.str(peer.remoteIp));
                    peerEntity.setName(peer.name);
                    peerEntity.setPeerBgpId(InetAddressUtils.str(peer.remoteBgpId));
                    boolean state = !peer.action.equals(Peer.Action.DOWN);
                    peerEntity.setState(state);
                    peerEntity.setL3VPNPeer(peer.l3vpn);
                    peerEntity.setTimestamp(Date.from(peer.timestamp));
                    peerEntity.setPrePolicy(peer.prePolicy);
                    peerEntity.setLocalIp(InetAddressUtils.str(peer.localIp));
                    peerEntity.setLocalBgpId(InetAddressUtils.str(peer.localBgpId));
                    peerEntity.setLocalPort(peer.localPort);
                    peerEntity.setLocalHoldTime(peer.advertisedHolddown);
                    peerEntity.setLocalAsn(peer.localAsn);
                    peerEntity.setRemotePort(peer.remotePort);
                    peerEntity.setRemoteHoldTime(peer.remoteHolddown);
                    peerEntity.setSentCapabilities(peer.advertisedCapabilities);
                    peerEntity.setReceivedCapabilities(peer.receivedCapabilities);
                    peerEntity.setBmpReason(peer.bmpReason);
                    peerEntity.setBgpErrCode(peer.bgpErrorCode);
                    peerEntity.setErrorText(peer.errorText);
                    peerEntity.setLocRib(peer.locRib);
                    peerEntity.setLocRibFiltered(peer.locRibFiltered);
                    peerEntity.setTableName(peer.tableName);
                    bmpPeers.add(peerEntity);
                } catch (Exception e) {
                    LOG.error("Exception while mapping Peer with peer addr '{}' to Peer entity", InetAddressUtils.str(peer.remoteIp), e);
                }
            }
        });
        return bmpPeers;
    }

    private static List<BmpBaseAttribute> buildBmpBaseAttributes(Message message) {

        List<BmpBaseAttribute> bmpBaseAttributes = new ArrayList<>();
        message.getRecords().forEach(record -> {
            if (record.getType().equals(Type.BASE_ATTRIBUTE)) {
                BaseAttribute baseAttribute = (BaseAttribute) record;
                try {
                    BmpBaseAttribute bmpBaseAttribute = new BmpBaseAttribute();
                    bmpBaseAttribute.setHashId(baseAttribute.hash);
                    bmpBaseAttribute.setPeerHashId(baseAttribute.peerHash);
                    bmpBaseAttribute.setOrigin(baseAttribute.origin);
                    bmpBaseAttribute.setAsPath(baseAttribute.asPath);
                    bmpBaseAttribute.setAsPathCount(baseAttribute.asPathCount);
                    bmpBaseAttribute.setOriginAs(baseAttribute.originAs);
                    bmpBaseAttribute.setNextHop(InetAddressUtils.str(baseAttribute.nextHop));
                    bmpBaseAttribute.setMed(baseAttribute.med);
                    bmpBaseAttribute.setLocalPref(baseAttribute.localPref);
                    bmpBaseAttribute.setAggregator(baseAttribute.aggregator);
                    bmpBaseAttribute.setCommunityList(baseAttribute.communityList);
                    bmpBaseAttribute.setExtCommunityList(baseAttribute.extCommunityList);
                    bmpBaseAttribute.setLargeCommunityList(baseAttribute.largeCommunityList);
                    bmpBaseAttribute.setClusterList(baseAttribute.clusterList);
                    bmpBaseAttribute.setAtomicAgg(baseAttribute.atomicAgg);
                    bmpBaseAttribute.setNextHopIpv4(baseAttribute.nextHopIpv4);
                    bmpBaseAttribute.setTimestamp(Date.from(baseAttribute.timestamp));
                    bmpBaseAttribute.setOriginatorId(baseAttribute.originatorId);
                    bmpBaseAttributes.add(bmpBaseAttribute);
                } catch (Exception e) {
                    LOG.error("Exception while mapping base attribute with hashId {} to BaseAttribute entity", baseAttribute.hash, e);
                }
            }

        });
        return bmpBaseAttributes;
    }

    private List<BmpUnicastPrefix> buildBmpUnicastPrefix(Message message) {

        List<BmpUnicastPrefix> bmpUnicastPrefixes = new ArrayList<>();
        message.getRecords().forEach(record -> {
            if (record.getType().equals(Type.UNICAST_PREFIX)) {
                BmpPeer bmpPeer;
                UnicastPrefix unicastPrefix = (UnicastPrefix) record;
                try {
                    BmpUnicastPrefix bmpUnicastPrefix = bmpUnicastPrefixDao.findByHashId(unicastPrefix.hash);
                    if (bmpUnicastPrefix == null) {
                        bmpUnicastPrefix = new BmpUnicastPrefix();
                        bmpUnicastPrefix.setFirstAddedTimestamp(Date.from(unicastPrefix.timestamp));
                        bmpPeer = bmpPeerDao.findByPeerHashId(unicastPrefix.peerHash);
                    } else {
                        bmpUnicastPrefix.setPrevBaseAttrHashId(bmpUnicastPrefix.getBaseAttrHashId());
                        bmpUnicastPrefix.setPrevWithDrawnState(bmpUnicastPrefix.isWithDrawn());
                        bmpPeer = bmpUnicastPrefix.getBmpPeer();
                    }
                    if (bmpPeer == null) {
                        LOG.warn("Peer entity with hashId '{}' doesn't exist yet", unicastPrefix.peerHash);
                        return;
                    }
                    bmpUnicastPrefix.setBmpPeer(bmpPeer);
                    bmpUnicastPrefix.setHashId(unicastPrefix.hash);
                    bmpUnicastPrefix.setBaseAttrHashId(unicastPrefix.baseAttrHash);
                    bmpUnicastPrefix.setIpv4(unicastPrefix.ipv4);
                    bmpUnicastPrefix.setOriginAs(unicastPrefix.originAs);
                    bmpUnicastPrefix.setPrefix(InetAddressUtils.str(unicastPrefix.prefix));
                    bmpUnicastPrefix.setPrefixLen(unicastPrefix.length);
                    bmpUnicastPrefix.setTimestamp(Date.from(unicastPrefix.timestamp));
                    boolean withDrawn = !unicastPrefix.action.equals(UnicastPrefix.Action.ADD);
                    bmpUnicastPrefix.setWithDrawn(withDrawn);
                    bmpUnicastPrefix.setPathId(unicastPrefix.pathId);
                    bmpUnicastPrefix.setLabels(unicastPrefix.labels);
                    bmpUnicastPrefix.setPrePolicy(unicastPrefix.prePolicy);
                    bmpUnicastPrefix.setAdjRibIn(unicastPrefix.adjIn);
                    bmpUnicastPrefixes.add(bmpUnicastPrefix);
                } catch (Exception e) {
                    LOG.error("Exception while mapping Unicast prefix with prefix {} to UnicastPrefix entity",
                            InetAddressUtils.str(unicastPrefix.prefix), e);
                }
            }
        });
        return bmpUnicastPrefixes;
    }

    private static List<BmpStatReports> buildBmpStatReports(Message message) {
        List<BmpStatReports> bmpStatReportsList = new ArrayList<>();
        message.getRecords().forEach(record -> {
            if (record.getType().equals(Type.BMP_STAT)) {
                Stat statreports = (Stat) record;
                BmpStatReports bmpStatReports = new BmpStatReports();
                bmpStatReports.setPeerHashId(statreports.peerHash);
                bmpStatReports.setPrefixesRejected(statreports.prefixesRejected);
                bmpStatReports.setKnownDupPrefixes(statreports.knownDupPrefixes);
                bmpStatReports.setKnownDupWithdraws(statreports.knownDupWithdraws);
                bmpStatReports.setUpdatesInvalidByClusterList(statreports.invalidClusterList);
                bmpStatReports.setUpdatesInvalidByAsPathLoop(statreports.invalidAsPath);
                bmpStatReports.setUpdatesInvalidByOriginatorId(statreports.invalidOriginatorId);
                bmpStatReports.setUpdatesInvalidByAsConfedLoop(statreports.invalidAsConfed);
                bmpStatReports.setNumRoutesAdjRibIn(statreports.prefixesPrePolicy);
                bmpStatReports.setNumROutesLocalRib(statreports.prefixesPostPolicy);
                bmpStatReports.setTimestamp(Date.from(statreports.timestamp));
                bmpStatReportsList.add(bmpStatReports);
            }
        });
        return bmpStatReportsList;

    }

    private BmpGlobalIpRib buildGlobalIpRib(PrefixByAS prefixByAS) {
        try {
            BmpGlobalIpRib bmpGlobalIpRib = bmpGlobalIpRibDao.findByPrefixAndAS(prefixByAS.getPrefix(), prefixByAS.getOriginAs());
            if (bmpGlobalIpRib == null) {
                bmpGlobalIpRib = new BmpGlobalIpRib();
            }
            bmpGlobalIpRib.setPrefix(prefixByAS.getPrefix());
            bmpGlobalIpRib.setPrefixLen(prefixByAS.getPrefixLen());
            bmpGlobalIpRib.setTimeStamp(prefixByAS.getTimeStamp());
            bmpGlobalIpRib.setRecvOriginAs(prefixByAS.getOriginAs());
            return bmpGlobalIpRib;
        } catch (Exception e) {
            LOG.error("Exception while mapping prefix {} to GlobalIpRib entity", prefixByAS.getPrefix(), e);
        }
        return null;

    }


    public BmpCollectorDao getBmpCollectorDao() {
        return bmpCollectorDao;
    }

    public void setBmpCollectorDao(BmpCollectorDao bmpCollectorDao) {
        this.bmpCollectorDao = bmpCollectorDao;
    }

    public BmpRouterDao getBmpRouterDao() {
        return bmpRouterDao;
    }

    public void setBmpRouterDao(BmpRouterDao bmpRouterDao) {
        this.bmpRouterDao = bmpRouterDao;
    }

    public BmpPeerDao getBmpPeerDao() {
        return bmpPeerDao;
    }

    public void setBmpPeerDao(BmpPeerDao bmpPeerDao) {
        this.bmpPeerDao = bmpPeerDao;
    }

    public BmpBaseAttributeDao getBmpBaseAttributeDao() {
        return bmpBaseAttributeDao;
    }

    public void setBmpBaseAttributeDao(BmpBaseAttributeDao bmpBaseAttributeDao) {
        this.bmpBaseAttributeDao = bmpBaseAttributeDao;
    }

    public BmpUnicastPrefixDao getBmpUnicastPrefixDao() {
        return bmpUnicastPrefixDao;
    }

    public void setBmpUnicastPrefixDao(BmpUnicastPrefixDao bmpUnicastPrefixDao) {
        this.bmpUnicastPrefixDao = bmpUnicastPrefixDao;
    }

    public BmpGlobalIpRibDao getBmpGlobalIpRibDao() {
        return bmpGlobalIpRibDao;
    }

    public void setBmpGlobalIpRibDao(BmpGlobalIpRibDao bmpGlobalIpRibDao) {
        this.bmpGlobalIpRibDao = bmpGlobalIpRibDao;
    }

    public SessionUtils getSessionUtils() {
        return sessionUtils;
    }

    public void setSessionUtils(SessionUtils sessionUtils) {
        this.sessionUtils = sessionUtils;
    }

    public CollectionAgentFactory getCollectionAgentFactory() {
        return collectionAgentFactory;
    }

    public void setCollectionAgentFactory(CollectionAgentFactory collectionAgentFactory) {
        this.collectionAgentFactory = collectionAgentFactory;
    }

    public InterfaceToNodeCache getInterfaceToNodeCache() {
        return interfaceToNodeCache;
    }

    public void setInterfaceToNodeCache(InterfaceToNodeCache interfaceToNodeCache) {
        this.interfaceToNodeCache = interfaceToNodeCache;
    }

    @Override
    public Stream<CollectionSetWithAgent> getCollectionSet() {
        List<CollectionSetWithAgent> collectionSetWithAgentList = new ArrayList<>();
        while (!collectionSetQueue.isEmpty()) {
            collectionSetWithAgentList.add(collectionSetQueue.poll());
        }
        return collectionSetWithAgentList.stream();
    }


    static class AsnKey {
        private final String peerHashId;
        private final Long peerAsn;

        public AsnKey(String peerHashId, Long peerAsn) {
            this.peerHashId = peerHashId;
            this.peerAsn = peerAsn;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AsnKey asnKey = (AsnKey) o;
            return Objects.equals(peerHashId, asnKey.peerHashId) &&
                    Objects.equals(peerAsn, asnKey.peerAsn);
        }

        @Override
        public int hashCode() {
            return Objects.hash(peerHashId, peerAsn);
        }
    }

    static class PrefixKey {
        private final String peerHashId;
        private final String prefix;
        private final Integer prefixLen;

        public PrefixKey(String peerHashId, String prefix, Integer prefixLen) {
            this.peerHashId = peerHashId;
            this.prefix = prefix;
            this.prefixLen = prefixLen;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PrefixKey prefixKey = (PrefixKey) o;
            return Objects.equals(peerHashId, prefixKey.peerHashId) &&
                    Objects.equals(prefix, prefixKey.prefix) &&
                    Objects.equals(prefixLen, prefixKey.prefixLen);
        }

        @Override
        public int hashCode() {
            return Objects.hash(peerHashId, prefix, prefixLen);
        }
    }
}
