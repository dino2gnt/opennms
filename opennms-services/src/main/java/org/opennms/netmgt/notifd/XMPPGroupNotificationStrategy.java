/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2006-2012 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2012 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.notifd;

import java.util.List;

import org.opennms.core.utils.Argument;
import org.opennms.core.utils.ThreadCategory;
import org.opennms.netmgt.config.NotificationManager;


/**
 * Implements NotificationStragey pattern used to send notifications using the
 * XMPP message protocol
 *
 * @author <a href="mailto:brozow@opennms.org">Mathew Brozowski</a>
 * @author <a href="mailto:dj@opennms.org">DJ Gregor</a>
 * @author <a href="mailto:jonathan@opennms.org">Jonathan Sartin</a>
 * @author <a href="mailto:brozow@opennms.org">Mathew Brozowski</a>
 * @author <a href="mailto:dj@opennms.org">DJ Gregor</a>
 * @author <a href="mailto:jonathan@opennms.org">Jonathan Sartin</a>
 * @author <a href="mailto:brozow@opennms.org">Mathew Brozowski</a>
 * @author <a href="mailto:dj@opennms.org">DJ Gregor</a>
 * @author <a href="mailto:jonathan@opennms.org">Jonathan Sartin</a>
 * @version $Id: $
 */
public class XMPPGroupNotificationStrategy implements NotificationStrategy {

	/**
	 * String used to identify the user to whom the XMPP message will be sent.
	 */
	private static final int XMPP_TO;

	/**
	 * Text of XMPP Message to be sent.
	 */
	private static final int XMPP_MESSAGE;

	/**
	 * The value of this constant indicates the number of XMPP constants
	 * defined.
	 */
	private static final int XMPP_MAX;

	/**
	 * Mapping of index values to meaningful strings.
	 */
	private static final String[] INDEX_TO_NAME;

	// Initialize constant class data

	static {

		XMPP_TO = 0;
		XMPP_MESSAGE = 1;
		XMPP_MAX = 2;

		INDEX_TO_NAME = new String[XMPP_MAX];

		INDEX_TO_NAME[XMPP_TO] = "To";
		INDEX_TO_NAME[XMPP_MESSAGE] = "Message";

	}

	/**
	 * <p>Constructor for XMPPGroupNotificationStrategy.</p>
	 */
	public XMPPGroupNotificationStrategy() {
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opennms.netmgt.notifd.NotificationStrategy#send(java.util.List)
	 */
	/** {@inheritDoc} */
	public int send(List<Argument> arguments) {

		try {

			String[] parsedArgs = this.parseArguments(arguments);

			XMPPNotificationManager xmppManager = XMPPNotificationManager.getInstance();

			xmppManager.sendGroupChat(parsedArgs[XMPP_TO],parsedArgs[XMPP_MESSAGE]);

		} catch (Throwable e) {
			ThreadCategory.getInstance(getClass()).error(e.getMessage());
			return 1;
		}

		return 0;

	}

	/**
	 * This method extracts the xmpp address and message text from the
	 * parameters passed in the notification.
	 * 
	 * @param arguments
	 * @return String[]
	 * @throws Exception
	 */

	private String[] parseArguments(List<Argument> arguments) throws Exception {

		String[] parsedArgs = new String[XMPP_MAX];

		for (int i = 0; i < arguments.size(); i++) {

			Argument arg = arguments.get(i);

			if (NotificationManager.PARAM_XMPP_ADDRESS.equals(arg.getSwitch())) {
				parsedArgs[XMPP_TO] = arg.getValue();
			} else if (NotificationManager.PARAM_TEXT_MSG.equals(arg
					.getSwitch())) {
				parsedArgs[XMPP_MESSAGE] = arg.getValue();
			}

		}

		for (int i = 0; i < XMPP_MAX; ++i) {
			if (parsedArgs[i] == null) {
				throw (new Exception(
						"Incomplete argument set, missing argument: "
								+ INDEX_TO_NAME[i]));
			}
		}

		return parsedArgs;

	}

}
