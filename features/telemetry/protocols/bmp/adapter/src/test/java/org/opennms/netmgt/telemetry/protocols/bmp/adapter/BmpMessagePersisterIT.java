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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.test.OpenNMSJUnit4ClassRunner;
import org.opennms.core.test.db.annotations.JUnitTemporaryDatabase;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.BmpMessageHandler;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.Message;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.Type;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.BaseAttribute;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Collector;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Peer;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Router;
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
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpUnicastPrefix;
import org.opennms.netmgt.telemetry.protocols.bmp.persistence.api.BmpUnicastPrefixDao;
import org.opennms.test.JUnitConfigurationEnvironment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;

import com.google.common.collect.ImmutableList;

@RunWith(OpenNMSJUnit4ClassRunner.class)
@ContextConfiguration(locations = {
        "classpath:/META-INF/opennms/applicationContext-commonConfigs.xml",
        "classpath:/META-INF/opennms/applicationContext-minimal-conf.xml",
        "classpath:/META-INF/opennms/applicationContext-soa.xml",
        "classpath:/META-INF/opennms/applicationContext-dao.xml",
        "classpath*:/META-INF/opennms/component-dao.xml",
        "classpath:/META-INF/opennms/mockEventIpcManager.xml",
        "classpath:/applicationContext-test-message-persister.xml",})
@JUnitConfigurationEnvironment
@JUnitTemporaryDatabase
public class BmpMessagePersisterIT {


    @Autowired
    private BmpMessageHandler bmpMessageHandler;

    @Autowired
    private BmpCollectorDao bmpCollectorDao;

    @Autowired
    private BmpRouterDao bmpRouterDao;

    @Autowired
    private BmpPeerDao bmpPeerDao;

    @Autowired
    private BmpUnicastPrefixDao bmpUnicastPrefixDao;

    @Autowired
    private BmpBaseAttributeDao bmpBaseAttributeDao;

    @Autowired
    private BmpGlobalIpRibDao bmpGlobalIpRibDao;

    @Test
    public void testPersistence() {

        // Persist collector
        final Collector collector = getCollector();
        Message msg = new Message("91e3a7ff9f5676ed6ae6fcd8a6b455ec", Type.COLLECTOR, ImmutableList.of(collector));
        bmpMessageHandler.handle(msg);
        List<BmpCollector> collectors = bmpCollectorDao.findAll();
        Assert.assertFalse(collectors.isEmpty());
        BmpCollector bmpCollector = collectors.get(0);
        Assert.assertTrue(bmpCollector.isState());

        // Persist router.
        final Router router1 = getRouter1();
        final Router router2 = getRouter2();
        msg = new Message("91e3a7ff9f5676ed6ae6fcd8a6b455ec", Type.ROUTER, ImmutableList.of(router1, router2));
        bmpMessageHandler.handle(msg);
        List<BmpRouter> routers = bmpRouterDao.findAll();
        assertEquals(2, routers.size());
        BmpRouter bmpRouter = routers.get(0);
        assertEquals(bmpRouter.getBmpCollector().getHashId(), "91e3a7ff9f5676ed6ae6fcd8a6b455ec");

        // Change collector state to stop and persist collector again. Routers should be down when collector is stopped.
        collector.action = Collector.Action.STOPPED;
        msg = new Message("91e3a7ff9f5676ed6ae6fcd8a6b455ec", Type.COLLECTOR, ImmutableList.of(collector));
        bmpMessageHandler.handle(msg);
        collectors = bmpCollectorDao.findAll();
        Assert.assertFalse(collectors.isEmpty());
        bmpCollector = collectors.get(0);
        Assert.assertFalse(bmpCollector.isState());
        routers = bmpRouterDao.findAll();
        Assert.assertFalse(routers.isEmpty());
        bmpRouter = routers.get(0);
        Assert.assertFalse(bmpRouter.isState());

        // Persist peer.
        Peer peer = getPeer();
        msg = new Message("91e3a7ff9f5676ed6ae6fcd8a6b455ec", Type.PEER, ImmutableList.of(peer));
        bmpMessageHandler.handle(msg);
        List<BmpPeer> peers = bmpPeerDao.findAll();
        Assert.assertFalse(peers.isEmpty());

        //Set Router state to TERM and then again INIT which should update Peers state to Down.
        router1.action = Router.Action.TERM;
        msg = new Message("91e3a7ff9f5676ed6ae6fcd8a6b455ec", Type.ROUTER, ImmutableList.of(router1));
        bmpMessageHandler.handle(msg);
        router1.action = Router.Action.INIT;
        router1.timestamp = Instant.now();
        msg = new Message("91e3a7ff9f5676ed6ae6fcd8a6b455ec", Type.ROUTER, ImmutableList.of(router1));
        bmpMessageHandler.handle(msg);
        peers = bmpPeerDao.findAll();
        Assert.assertTrue(peers.size() == 1);
        BmpPeer bmpPeer = peers.get(0);
        Assert.assertFalse(bmpPeer.isState());

        UnicastPrefix unicastPrefix = getUnicastPrefix();
        msg = new Message("91e3a7ff9f5676ed6ae6fcd8a6b455ec", Type.UNICAST_PREFIX, ImmutableList.of(unicastPrefix));
        bmpMessageHandler.handle(msg);
        List<BmpUnicastPrefix> prefixList = bmpUnicastPrefixDao.findAll();
        Assert.assertFalse(prefixList.isEmpty());
        List<BmpGlobalIpRib> bmpGlobalIpRibs = bmpGlobalIpRibDao.findAll();
        Assert.assertFalse(bmpGlobalIpRibs.isEmpty());

        //New Peer message should remove all previous prefixes.
        peer.action = Peer.Action.DOWN;
        peer.timestamp = Instant.now();
        msg = new Message("91e3a7ff9f5676ed6ae6fcd8a6b455ec", Type.PEER, ImmutableList.of(peer));
        bmpMessageHandler.handle(msg);
        prefixList = bmpUnicastPrefixDao.findAll();
        Assert.assertTrue(prefixList.isEmpty());

        //Persist BMP Base attributes.
        BaseAttribute baseAttribute = getBmpBaseAttribute();
        msg = new Message("91e3a7ff9f5676ed6ae6fcd8a6b455ec", Type.BASE_ATTRIBUTE, ImmutableList.of(baseAttribute));
        bmpMessageHandler.handle(msg);
        List<BmpBaseAttribute> bmpBaseAttributes = bmpBaseAttributeDao.findAll();
        Assert.assertFalse(bmpBaseAttributes.isEmpty());

    }

    @Test
    public void testStats() {
        // Mock unicast prefix element
        BmpUnicastPrefix bmpUnicastPrefix = new BmpUnicastPrefix();
        BmpPeer bmpPeer = new BmpPeer();
        final String peerHashId = "61e5a7ff9f5433ed6ae6fcd9a2b432gf";
        final String baseAttrHashId = "23212a7ff9f5433ed6ae6fcd9a2b432gf";
        final Long originAs = 4567L;
        final String prefix = "10.11.12.110";
        bmpPeer.setHashId(peerHashId);
        bmpUnicastPrefix.setBmpPeer(bmpPeer);
        bmpUnicastPrefix.setBaseAttrHashId(baseAttrHashId);
        bmpUnicastPrefix.setOriginAs(4567L);
        bmpUnicastPrefix.setPrefix(prefix);
        bmpUnicastPrefix.setPrefixLen(4);
        bmpUnicastPrefix.setWithDrawn(false);
        BmpMessagePersister bmpMessagePersister = new BmpMessagePersister();
        bmpMessagePersister.updateStats(bmpUnicastPrefix);
        // Updates should get updated but withdraws should be null
        assertTrue(bmpMessagePersister.getUpdatesByPeer().get(peerHashId) == 1L);
        assertTrue(bmpMessagePersister.getUpdatesByAsn()
                .get(new BmpMessagePersister.AsnKey(peerHashId, originAs)) == 1L);
        assertTrue(bmpMessagePersister.getUpdatesByPrefix()
                .get(new BmpMessagePersister.PrefixKey(peerHashId, prefix, 4)) == 1L);
        // Withdraws should be null
        assertNull(bmpMessagePersister.getWithdrawsByPeer().get(peerHashId));
        assertNull(bmpMessagePersister.getWithdrawsByAsn()
                .get(new BmpMessagePersister.AsnKey(peerHashId, originAs)));
        assertNull(bmpMessagePersister.getWithdrawsByPrefix()
                .get(new BmpMessagePersister.PrefixKey(peerHashId, prefix, 4)));

        // Update with different base attributes.
        bmpUnicastPrefix.setBaseAttrHashId("41e5a7ff9f5422ed6ae6fcd9a2b432gf");
        bmpUnicastPrefix.setPrevBaseAttrHashId(baseAttrHashId);
        bmpUnicastPrefix.setId(1L);
        bmpMessagePersister.updateStats(bmpUnicastPrefix);
        assertTrue(bmpMessagePersister.getUpdatesByAsn()
                .get(new BmpMessagePersister.AsnKey(peerHashId, originAs)) == 2L);

        // Send withdrawn
        bmpUnicastPrefix.setWithDrawn(true);
        bmpMessagePersister.updateStats(bmpUnicastPrefix);
        assertTrue(bmpMessagePersister.getWithdrawsByAsn()
                .get(new BmpMessagePersister.AsnKey(peerHashId, originAs)) == 1L);

    }


    private static Collector getCollector() {
        final Collector collector = new Collector();
        collector.action = Collector.Action.CHANGE;
        collector.sequence = 8L;
        collector.adminId = "collector";
        collector.hash = "91e3a7ff9f5676ed6ae6fcd8a6b455ec";
        collector.routers = Collections.singletonList(InetAddressUtils.addr("10.10.10.10"));
        long timeMicros = 1_582_456_123_795_452L;
        collector.timestamp = Instant.EPOCH.plus(timeMicros, ChronoUnit.MICROS);
        return collector;
    }

    private static Router getRouter1() {
        final Router router = new Router();
        router.action = Router.Action.FIRST;
        router.sequence = 16L;
        router.hash = "81e4a7ff8f5673ed6ae6fcd9a3b452bg";
        router.bgpId = InetAddressUtils.addr("10.12.11.12");
        router.name = "bmp-ex1";
        router.ipAddress = InetAddressUtils.addr("10.12.11.11");
        router.timestamp = Instant.now();
        return router;
    }

    private static Router getRouter2() {
        final Router router = new Router();
        router.action = Router.Action.FIRST;
        router.sequence = 15L;
        router.hash = "7134d7ff8f5673re6ae6fgf8a3r452be";
        router.bgpId = InetAddressUtils.addr("10.12.11.10");
        router.name = "bmp-ex2";
        router.ipAddress = InetAddressUtils.addr("10.12.11.9");
        router.timestamp = Instant.now();
        return router;
    }

    private static Peer getPeer() {
        final Peer peer = new Peer();
        peer.action = Peer.Action.UP;
        peer.sequence = 64L;
        peer.hash = "61e5a7ff9f5433ed6ae6fcd9a2b432gf";
        peer.routerHash = "81e4a7ff8f5673ed6ae6fcd9a3b452bg";
        peer.name = "peer1";
        peer.remoteBgpId = InetAddressUtils.addr("10.23.12.34");
        peer.localBgpId = InetAddressUtils.addr("10.12.11.11");
        peer.peerRd = "peer1";
        peer.remoteAsn = 8242L;
        peer.remotePort = 8765;
        peer.localPort = 9878;
        peer.localAsn = 8142L;
        peer.localIp = InetAddressUtils.addr("10.12.11.12");
        peer.remoteIp = InetAddressUtils.addr("10.23.12.32");
        peer.remoteHolddown = 128L;
        peer.advertisedHolddown = 984L;
        peer.timestamp = Instant.now();
        peer.infoData = "BMP Peer UP message informational data";
        peer.advertisedCapabilities = "BMP Peer advertised capabilities";
        peer.receivedCapabilities = "BMP Peer received capabilities";
        return peer;
    }


    private static UnicastPrefix getUnicastPrefix() {

        UnicastPrefix unicastPrefix = new UnicastPrefix();
        unicastPrefix.action = UnicastPrefix.Action.ADD;
        unicastPrefix.sequence = 15L;
        unicastPrefix.hash = "83e12a7ff8f5673es6ae6fcd9a3b345uy";
        unicastPrefix.peerHash = "61e5a7ff9f5433ed6ae6fcd9a2b432gf";
        unicastPrefix.baseAttrHash = "23212a7ff9f5433ed6ae6fcd9a2b432gf";
        unicastPrefix.ipv4 = true;
        unicastPrefix.origin = "ibgp";
        unicastPrefix.originAs = 7075L;
        unicastPrefix.adjIn = false;
        unicastPrefix.prefix = InetAddressUtils.addr("10.0.0.1");
        unicastPrefix.length = 1;
        unicastPrefix.timestamp = Instant.now();
        unicastPrefix.med = 5L;
        unicastPrefix.localPref = 110L;
        return unicastPrefix;

    }

    private static BaseAttribute getBmpBaseAttribute() {
        BaseAttribute bmpBaseAttribute = new BaseAttribute();
        bmpBaseAttribute.hash = "23212a7ff9f5433ed6ae6fcd9a2b432gf";
        bmpBaseAttribute.peerHash = "61e5a7ff9f5433ed6ae6fcd9a2b432gf";
        bmpBaseAttribute.action = BaseAttribute.Action.ADD;
        bmpBaseAttribute.asPath = "64512 64513 {64514}";
        bmpBaseAttribute.asPathCount = 3;
        bmpBaseAttribute.origin = "ebgp";
        bmpBaseAttribute.originAs = 7075L;
        bmpBaseAttribute.atomicAgg = false;
        bmpBaseAttribute.nextHop = InetAddressUtils.addr("10.0.1.2");
        bmpBaseAttribute.med = 4L;
        bmpBaseAttribute.localPref = 110L;
        bmpBaseAttribute.aggregator = "";
        bmpBaseAttribute.communityList = "100:100, 200:200";
        bmpBaseAttribute.largeCommunityList = "100:100:100,200:200:200";
        bmpBaseAttribute.nextHopIpv4 = true;
        bmpBaseAttribute.timestamp = Instant.now();
        return bmpBaseAttribute;
    }

}
