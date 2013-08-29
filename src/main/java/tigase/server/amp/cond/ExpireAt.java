/*
 * ExpireAt.java
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



package tigase.server.amp.cond;

//~--- non-JDK imports --------------------------------------------------------

import tigase.server.amp.ConditionIfc;
import tigase.server.Packet;

import tigase.xml.Element;

//~--- JDK imports ------------------------------------------------------------

import java.text.ParseException;
import java.text.SimpleDateFormat;

import java.util.Date;
import java.util.logging.Logger;
import java.util.Map;

/**
 * Created: Apr 27, 2010 5:36:39 PM
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class ExpireAt
				implements ConditionIfc {
	/** Field description */
	public static final String NAME = "expire-at";

	/**
	 * Private logger for class instances.
	 */
	private static Logger log = Logger.getLogger(ExpireAt.class.getName());

	//~--- fields ---------------------------------------------------------------

	private final SimpleDateFormat formatter = new SimpleDateFormat(
			"yyyy-MM-dd'T'HH:mm:ss.SSSZ");

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 *
	 * @param packet
	 * @param rule
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	@Override
	public boolean match(Packet packet, Element rule) {
		String value = rule.getAttributeStaticStr("value");

		if (value != null) {
			try {
				Date val_date = null;

				synchronized (formatter) {
					val_date = formatter.parse(value);
				}

				return val_date.before(new Date());
			} catch (ParseException ex) {
				log.info("Incorrect " + NAME + " condition value for rule: " + rule);
			}
		} else {
			log.info("No value set for rule: " + rule);
		}

		return false;
	}

	//~--- get methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 * @return a value of <code>String</code>
	 */
	@Override
	public String getName() {
		return NAME;
	}
}


//~ Formatted in Tigase Code Convention on 13/08/28
