/*
 * StreamError.java
 *
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2013 "Tigase, Inc." <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 */



package tigase.server.xmppserver.proc;

//~--- non-JDK imports --------------------------------------------------------

import tigase.server.Packet;
import tigase.server.xmppserver.ConnectionHandlerIfc;
import tigase.server.xmppserver.S2SIOService;
import tigase.server.xmppserver.S2SProcessor;

//~--- JDK imports ------------------------------------------------------------

import java.util.logging.Logger;
import java.util.Map;
import java.util.Queue;

/**
 * Created: Dec 9, 2010 2:00:08 PM
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class StreamError
				extends S2SAbstractProcessor {
	private static final Logger log = Logger.getLogger(StreamError.class.getName());

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param p
	 * @param serv
	 * @param results
	 *
	 *
	 *
	 * @return a value of boolean
	 */
	@Override
	public boolean process(Packet p, S2SIOService serv, Queue<Packet> results) {
		if (p.getElemName() == "error") {
			serv.stop();

			return true;
		} else {
			return false;
		}
	}
}


//~ Formatted in Tigase Code Convention on 13/08/28
