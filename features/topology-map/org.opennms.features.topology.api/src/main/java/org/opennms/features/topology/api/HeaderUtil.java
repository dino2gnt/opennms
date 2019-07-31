/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2019 The OpenNMS Group, Inc.
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

package org.opennms.features.topology.api;

import com.vaadin.server.ClientConnector;

public class HeaderUtil {
    public static ClientConnector.AttachListener getAttachListener() {
        return new ClientConnector.AttachListener() {
            @Override
            public void attach(ClientConnector.AttachEvent event) {
                checkHeaderVisibility();
            }
        };
    }

    public static void checkHeaderVisibility() {
        com.vaadin.ui.JavaScript.getCurrent().execute(
                "if (window.location != window.parent.location && window.name.indexOf('-with-header') == -1) {$('#header').hide(); $('body.fixed-nav').attr('style', 'padding-top: 0px !important');}"
        );
    }
}
