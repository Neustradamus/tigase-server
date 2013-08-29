/*
 * ComponentConnectionManager.java
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



package tigase.server.xmppcomponent;

//~--- non-JDK imports --------------------------------------------------------

import tigase.disco.ServiceEntity;
import tigase.disco.ServiceIdentity;
import tigase.disco.XMPPService;

import tigase.net.ConnectionType;
import tigase.net.SocketType;

import tigase.server.ConnectionManager;
import tigase.server.Packet;

import tigase.util.Algorithms;
import tigase.util.TigaseStringprepException;

import tigase.xml.Element;

import tigase.xmpp.Authorization;
import tigase.xmpp.BareJID;
import tigase.xmpp.JID;
import tigase.xmpp.PacketErrorTypeException;
import tigase.xmpp.XMPPIOService;

//~--- JDK imports ------------------------------------------------------------

import java.security.NoSuchAlgorithmException;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Class ComponentConnectionManager
 *
 * Created: Tue Nov 22 07:07:11 2005
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class ComponentConnectionManager
				extends ConnectionManager<XMPPIOService<Object>>
				implements XMPPService {
	/** Field description */
	public static final String IDENTITY_TYPE_KEY = "identity-type";

	/** Field description */
	public static final String IDENTITY_TYPE_VAL = "generic";

	/** Field description */
	public static final String PACK_ROUTED_KEY = "pack-routed";

	/** Field description */
	public static final String PORT_ROUTING_TABLE_PROP_KEY = "routing-table";

	/** Field description */
	public static final String RETURN_SERVICE_DISCO_KEY = "service-disco";

	/** Field description */
	public static final String SECRET_PROP_KEY = "secret";

	/** Field description */
	public static final boolean RETURN_SERVICE_DISCO_VAL = true;

	/**
	 * Variable <code>log</code> is a class logger.
	 */
	private static final Logger log = Logger.getLogger(ComponentConnectionManager.class
			.getName());

	//~--- fields ---------------------------------------------------------------

	/** Field description */
	public String PORT_LOCAL_HOST_PROP_VAL = "localhost";

	/** Field description */
	public String PORT_REMOTE_HOST_PROP_VAL = "comp-1.localhost";

	/** Field description */
	public int[] PORTS = { 5555 };

	/** Field description */
	public ConnectionType PORT_TYPE_PROP_VAL = ConnectionType.accept;

	/** Field description */
	public SocketType PORT_SOCKET_PROP_VAL = SocketType.plain;

	/** Field description */
	public String SECRET_PROP_VAL = "someSecret";

	/** Field description */
	public String[] PORT_ROUTING_TABLE_PROP_VAL = { PORT_REMOTE_HOST_PROP_VAL,
			".*@" + PORT_REMOTE_HOST_PROP_VAL, ".*\\." + PORT_REMOTE_HOST_PROP_VAL };

	/** Field description */
	public boolean        PACK_ROUTED_VAL = false;
	private String        service_id      = "it doesn't matter";
	private ServiceEntity serviceEntity   = null;
	private boolean       pack_routed     = PACK_ROUTED_VAL;

	// private boolean service_disco = RETURN_SERVICE_DISCO_VAL;
	private String identity_type = IDENTITY_TYPE_VAL;

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 */
	@Override
	public void processPacket(Packet packet) {
		if (log.isLoggable(Level.FINER)) {
			log.finer("Processing packet: " + packet.getElemName() + ", type: " + packet
					.getType());
		}
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Processing packet: " + packet);
		}
		if ((packet.getStanzaTo() != null) && packet.getStanzaTo().equals(getComponentId())) {
			try {
				addOutPacket(Authorization.FEATURE_NOT_IMPLEMENTED.getResponseMessage(packet,
						"Not implemented", true));
			} catch (PacketErrorTypeException e) {
				log.warning("Packet processing exception: " + e);
			}

			return;
		}
		if (pack_routed) {
			writePacketToSocket(packet.packRouted());
		} else {
			writePacketToSocket(packet);
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param serv
	 *
	 *
	 *
	 * @return a value of Queue<Packet>
	 */
	@Override
	public Queue<Packet> processSocketData(XMPPIOService<Object> serv) {
		Packet p = null;

		while ((p = serv.getReceivedPackets().poll()) != null) {
			if (log.isLoggable(Level.FINER)) {
				log.finer("Processing packet: " + p.getElemName() + ", type: " + p.getType());
			}
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Processing socket data: " + p);
			}
			if (p.getElemName().equals("handshake")) {
				processHandshake(p, serv);
			} else {
				Packet result = p;

				if (p.isRouted()) {
					try {
						result = p.unpackRouted();
					} catch (TigaseStringprepException ex) {
						log.warning("Packet stringprep addressing problem, dropping packet: " + p);

						return null;
					}
				}    // end of if (p.isRouted())
				addOutPacket(result);
			}
		}        // end of while ()

		return null;
	}

	/**
	 * Method description
	 *
	 *
	 * @param port_props
	 */
	@Override
	public void reconnectionFailed(Map<String, Object> port_props) {

		// TODO: handle this somehow
	}

	/**
	 * Method description
	 *
	 *
	 * @param serv
	 */
	@Override
	public void serviceStarted(XMPPIOService<Object> serv) {
		super.serviceStarted(serv);
		if (log.isLoggable(Level.FINEST)) {
			log.finest("c2c connection opened: " + serv.getRemoteAddress() + ", type: " + serv
					.connectionType().toString() + ", id=" + serv.getUniqueId());
		}

//  String addr =
//    (String)service.getSessionData().get(PORT_REMOTE_HOST_PROP_KEY);
//  addRouting(addr);
		// addRouting(serv.getRemoteHost());
		switch (serv.connectionType()) {
		case connect :

			// Send init xmpp stream here
			// XMPPIOService serv = (XMPPIOService)service;
			String compName = (String) serv.getSessionData().get(PORT_LOCAL_HOST_PROP_KEY);
			String data = "<stream:stream" + " xmlns='jabber:component:accept'" +
					" xmlns:stream='http://etherx.jabber.org/streams'" + " to='" + compName + "'" +
					">";

			if (log.isLoggable(Level.FINEST)) {
				log.finest("cid: " + (String) serv.getSessionData().get("cid") + ", sending: " +
						data);
			}
			serv.xmppStreamOpen(data);

			break;

		default :

			// Do nothing, more data should come soon...
			break;
		}    // end of switch (service.connectionType())
	}

	/**
	 * Method description
	 *
	 *
	 * @param service
	 *
	 *
	 *
	 * @return a value of boolean
	 */
	@Override
	public boolean serviceStopped(XMPPIOService<Object> service) {
		boolean result = super.serviceStopped(service);

		if (result) {
			Map<String, Object> sessionData = service.getSessionData();
			String[]            routings = (String[]) sessionData.get(
					PORT_ROUTING_TABLE_PROP_KEY);

			updateRoutings(routings, false);

			ConnectionType type = service.connectionType();

			if (type == ConnectionType.connect) {
				addWaitingTask(sessionData);

				// reconnectService(sessionData, connectionDelay);
			}    // end of if (type == ConnectionType.connect)

			// removeRouting(serv.getRemoteHost());
			String addr = (String) sessionData.get(PORT_REMOTE_HOST_PROP_KEY);

			removeComponentDomain(addr);
			if (log.isLoggable(Level.FINE)) {
				log.fine("Disonnected from: " + addr);
			}
			updateServiceDiscovery(addr, "XEP-0114 disconnected");
		}

		return result;
	}

	/**
	 * Method description
	 *
	 *
	 * @param service
	 */
	@Override
	public void tlsHandshakeCompleted(XMPPIOService<Object> service) {}

	/**
	 * Method description
	 *
	 *
	 * @param serv
	 */
	@Override
	public void xmppStreamClosed(XMPPIOService<Object> serv) {
		if (log.isLoggable(Level.FINER)) {
			log.finer("Stream closed.");
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param service
	 * @param attribs
	 *
	 *
	 *
	 * @return a value of String
	 */
	@Override
	public String xmppStreamOpened(XMPPIOService<Object> service, Map<String,
			String> attribs) {
		if (log.isLoggable(Level.FINER)) {
			log.finer("Stream opened: " + attribs.toString());
		}
		switch (service.connectionType()) {
		case connect : {
			String id = attribs.get("id");

			service.getSessionData().put(XMPPIOService.SESSION_ID_KEY, id);

			String secret = (String) service.getSessionData().get(SECRET_PROP_KEY);

			try {
				String digest = Algorithms.hexDigest(id, secret, "SHA");

				if (log.isLoggable(Level.FINEST)) {
					log.finest("Calculating digest: id=" + id + ", secret=" + secret +
							", digest=" + digest);
				}

				return "<handshake>" + digest + "</handshake>";
			} catch (NoSuchAlgorithmException e) {
				log.log(Level.SEVERE, "Can not generate digest for pass phrase.", e);

				return null;
			}
		}

		case accept : {
			String hostname = attribs.get("to");

			service.getSessionData().put(XMPPIOService.HOSTNAME_KEY, hostname);

			String id = UUID.randomUUID().toString();

			service.getSessionData().put(XMPPIOService.SESSION_ID_KEY, id);

			return "<stream:stream" + " xmlns='jabber:component:accept'" +
					" xmlns:stream='http://etherx.jabber.org/streams'" + " from='" + hostname +
					"'" + " id='" + id + "'" + ">";
		}

		default :

			// Do nothing, more data should come soon...
			break;
		}    // end of switch (service.connectionType())

		return null;
	}

	//~--- get methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param params
	 *
	 *
	 *
	 * @return a value of Map<String,Object>
	 */
	@Override
	public Map<String, Object> getDefaults(Map<String, Object> params) {
		String config_type = (String) params.get("config-type");

		if (config_type.equals(GEN_CONFIG_SM)) {
			PACK_ROUTED_VAL    = true;
			PORT_TYPE_PROP_VAL = ConnectionType.accept;
		}
		if (config_type.equals(GEN_CONFIG_CS)) {
			PACK_ROUTED_VAL    = true;
			PORT_TYPE_PROP_VAL = ConnectionType.connect;
			PORT_IFC_PROP_VAL  = new String[] { "localhost" };
		}

		boolean def_found = false;

		for (String key : params.keySet()) {
			String gen_ext_comp = GEN_EXT_COMP;

			if (key.startsWith(GEN_EXT_COMP)) {
				String end = key.substring(GEN_EXT_COMP.length());

				if (getName().endsWith(end)) {
					gen_ext_comp = key;
				}    // end of if (getName().endsWith(end))
				if (params.get(gen_ext_comp) != null) {
					def_found = true;
					log.config("Found default configuration: " + (String) params.get(gen_ext_comp));

					String[] comp_params = ((String) params.get(gen_ext_comp)).split(",");
					int      idx         = 0;

					if (comp_params.length >= idx + 1) {
						PORT_LOCAL_HOST_PROP_VAL = comp_params[idx++];
						log.config("Setting PORT_LOCAL_HOST_PROP_VAL to " + PORT_LOCAL_HOST_PROP_VAL);
					}
					if (comp_params.length >= idx + 1) {
						PORT_REMOTE_HOST_PROP_VAL = comp_params[idx++];
						log.config("Setting PORT_REMOTE_HOST_PROP_VAL to " +
								PORT_REMOTE_HOST_PROP_VAL);
						PORT_ROUTING_TABLE_PROP_VAL = new String[] { PORT_REMOTE_HOST_PROP_VAL };
						if (config_type.equals(GEN_CONFIG_CS)) {
							PORT_IFC_PROP_VAL = new String[] { PORT_REMOTE_HOST_PROP_VAL };
						}
					}
					if (comp_params.length >= idx + 1) {
						try {
							PORTS[0] = Integer.decode(comp_params[idx++]);
						} catch (NumberFormatException e) {
							log.warning("Incorrect component port number: " + comp_params[idx - 1]);
							PORTS[0] = 5555;
						}
						log.config("Setting PORTS[0] to " + PORTS[0]);
					}
					if (comp_params.length >= idx + 1) {
						SECRET_PROP_VAL = comp_params[idx++];
						log.config("Setting SECRET_PROP_VAL to " + SECRET_PROP_VAL);
					}
					if (comp_params.length >= idx + 1) {
						if (comp_params[idx++].equals("ssl")) {
							PORT_SOCKET_PROP_VAL = SocketType.plain;
						}
						log.config("Setting PORT_SOCKET_PROP_VAL to " + PORT_SOCKET_PROP_VAL);
					}
					if (comp_params.length >= idx + 1) {
						if (comp_params[idx++].equals("accept")) {
							PORT_TYPE_PROP_VAL = ConnectionType.accept;
						} else {
							PORT_TYPE_PROP_VAL = ConnectionType.connect;
						}
						log.config("Setting PORT_TYPE_PROP_VAL to " + PORT_TYPE_PROP_VAL);
					}
					if (comp_params.length >= idx + 1) {
						PORT_ROUTING_TABLE_PROP_VAL = new String[] { comp_params[idx++] };
					} else {
						if (config_type.equals(GEN_CONFIG_COMP)) {

							// This is specialized configuration for a single
							// external component so all traffic should go through
							// the external component (it acts as like s2s component)
							PORT_ROUTING_TABLE_PROP_VAL = new String[] { ".*" };
						} else {
							String regex_host = PORT_REMOTE_HOST_PROP_VAL.replace(".", "\\.");

							PORT_ROUTING_TABLE_PROP_VAL = new String[] { regex_host, ".*@" + regex_host,
									".*\\." + regex_host };
						}
					}

					break;
				}
			}
		}
		if (!def_found) {
			PORT_LOCAL_HOST_PROP_VAL  = "localhost";
			PORT_REMOTE_HOST_PROP_VAL = getName() + ".localhost";

			String regex_host = PORT_REMOTE_HOST_PROP_VAL.replace(".", "\\.");

			PORT_ROUTING_TABLE_PROP_VAL = new String[] { regex_host, ".*@" + regex_host,
					".*\\." + regex_host };
		}    // end of if (!def_found)

		Map<String, Object> props = super.getDefaults(params);

		props.put(PACK_ROUTED_KEY, PACK_ROUTED_VAL);
		props.put(RETURN_SERVICE_DISCO_KEY, RETURN_SERVICE_DISCO_VAL);
		props.put(IDENTITY_TYPE_KEY, IDENTITY_TYPE_VAL);

		return props;
	}

	/**
	 * Method description
	 *
	 *
	 * @param from
	 *
	 *
	 *
	 * @return a value of List<Element>
	 */
	@Override
	public List<Element> getDiscoFeatures(JID from) {
		return null;
	}

	/**
	 * Method description
	 *
	 *
	 * @param node
	 * @param jid
	 * @param from
	 *
	 *
	 *
	 * @return a value of Element
	 */
	@Override
	public Element getDiscoInfo(String node, JID jid, JID from) {
		if ((jid != null) && getName().equals(jid.getLocalpart())) {
			return serviceEntity.getDiscoInfo(node);
		}

		return null;
	}

	/**
	 * Method description
	 *
	 *
	 * @param node
	 * @param jid
	 * @param from
	 *
	 *
	 *
	 * @return a value of List<Element>
	 */
	@Override
	public List<Element> getDiscoItems(String node, JID jid, JID from) {
		if (getName().equals(jid.getLocalpart())) {
			return serviceEntity.getDiscoItems(node, null);
		} else {
			return Arrays.asList(serviceEntity.getDiscoItem(null, BareJID.toString(getName(),
					jid.toString())));
		}
	}

	//~--- set methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param props
	 */
	@Override
	public void setProperties(Map<String, Object> props) {
		super.setProperties(props);
		pack_routed = (Boolean) props.get(PACK_ROUTED_KEY);

		// service_disco = (Boolean)props.get(RETURN_SERVICE_DISCO_KEY);
		identity_type = (String) props.get(IDENTITY_TYPE_KEY);

		// serviceEntity = new ServiceEntity(getName(), "external", "XEP-0114");
		serviceEntity = new ServiceEntity("XEP-0114 " + getName(), null, "XEP-0114");
		serviceEntity.addIdentities(new ServiceIdentity("component", identity_type,
				"XEP-0114 " + getName()));
	}

	//~--- get methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @return a value of int[]
	 */
	@Override
	protected int[] getDefPlainPorts() {
		return PORTS;
	}

	/**
	 * Method <code>getMaxInactiveTime</code> returns max keep-alive time
	 * for inactive connection. we shoulnd not really close external component
	 * connection at all, so let's say something like: 1000 days...
	 *
	 *  a <code>long</code> value
	 *
	 * @return a value of long
	 */
	@Override
	protected long getMaxInactiveTime() {
		return 1000 * 24 * HOUR;
	}

	/**
	 * Method description
	 *
	 *
	 * @param port
	 *
	 * @return a value of Map<String,Object>
	 */
	@Override
	protected Map<String, Object> getParamsForPort(int port) {
		Map<String, Object> defs = new TreeMap<String, Object>();

		defs.put(SECRET_PROP_KEY, SECRET_PROP_VAL);
		defs.put(PORT_LOCAL_HOST_PROP_KEY, PORT_LOCAL_HOST_PROP_VAL);
		defs.put(PORT_ROUTING_TABLE_PROP_KEY, PORT_ROUTING_TABLE_PROP_VAL);
		defs.put(PORT_TYPE_PROP_KEY, PORT_TYPE_PROP_VAL);
		defs.put(PORT_SOCKET_PROP_KEY, PORT_SOCKET_PROP_VAL);
		defs.put(PORT_REMOTE_HOST_PROP_KEY, PORT_REMOTE_HOST_PROP_VAL);
		defs.put(PORT_IFC_PROP_KEY, PORT_IFC_PROP_VAL);
		defs.put(MAX_RECONNECTS_PROP_KEY, (int) (30 * MINUTE));

		return defs;
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 *
	 * @return a value of String
	 */
	@Override
	protected String getServiceId(Packet packet) {
		return service_id;
	}

	/**
	 * Method description
	 *
	 *
	 * @param serv
	 *
	 * @return a value of String
	 */
	@Override
	protected String getUniqueId(XMPPIOService<Object> serv) {

		// return (String)serv.getSessionData().get(PORT_REMOTE_HOST_PROP_KEY);
		return service_id;
	}

	/**
	 * Method description
	 *
	 *
	 * @return a value of XMPPIOService<Object>
	 */
	@Override
	protected XMPPIOService<Object> getXMPPIOServiceInstance() {
		return new XMPPIOService<Object>();
	}

	//~--- methods --------------------------------------------------------------

	private void processHandshake(Packet p, XMPPIOService<Object> serv) {
		switch (serv.connectionType()) {
		case connect : {
			String data = p.getElemCData();

			if (data == null) {
				String[] routings = (String[]) serv.getSessionData().get(
						PORT_ROUTING_TABLE_PROP_KEY);

				updateRoutings(routings, true);

				String addr = (String) serv.getSessionData().get(PORT_REMOTE_HOST_PROP_KEY);

				addComponentDomain(addr);
				if (log.isLoggable(Level.FINE)) {
					log.fine("Connected to: " + addr);
				}
				updateServiceDiscovery(addr, "XEP-0114 connected");
			} else {
				log.warning("Incorrect packet received: " + p);
			}

			break;
		}

		case accept : {
			String digest = p.getElemCData();
			String id     = (String) serv.getSessionData().get(XMPPIOService.SESSION_ID_KEY);
			String secret = (String) serv.getSessionData().get(SECRET_PROP_KEY);

			try {
				String loc_digest = Algorithms.hexDigest(id, secret, "SHA");

				if (log.isLoggable(Level.FINEST)) {
					log.finest("Calculating digest: id=" + id + ", secret=" + secret +
							", digest=" + loc_digest);
				}
				if ((digest != null) && digest.equals(loc_digest)) {
					Packet resp = Packet.packetInstance(new Element("handshake"));

					writePacketToSocket(serv, resp);

					String[] routings = (String[]) serv.getSessionData().get(
							PORT_ROUTING_TABLE_PROP_KEY);

					updateRoutings(routings, true);

					String addr = (String) serv.getSessionData().get(XMPPIOService.HOSTNAME_KEY);

					addComponentDomain(addr);
					if (log.isLoggable(Level.FINE)) {
						log.fine("Connected to: " + addr);
					}
					updateServiceDiscovery(addr, "XEP-0114 connected");
				} else {
					log.info("Handshaking passwords don't match, disconnecting...");
					serv.stop();
				}
			} catch (Exception e) {
				log.log(Level.SEVERE, "Handshaking error.", e);
			}

			break;
		}

		default :

			// Do nothing, more data should come soon...
			break;
		}    // end of switch (service.connectionType())
	}

	private void updateRoutings(String[] routings, boolean add) {
		if (add) {
			log.info("Adding routings: " + Arrays.toString(routings));
			for (String route : routings) {
				try {
					addRegexRouting(route);
				} catch (Exception e) {
					log.warning("Can not add regex routing '" + route + "' : " + e);
				}
			}
		} else {
			log.info("Removing routings: " + Arrays.toString(routings));
			for (String route : routings) {
				try {
					removeRegexRouting(route);
				} catch (Exception e) {
					log.warning("Can not remove regex routing '" + route + "' : " + e);
				}
			}
		}
	}

	private void updateServiceDiscovery(String jid, String name) {
		ServiceEntity item = new ServiceEntity(jid, null, name);

		// item.addIdentities(new ServiceIdentity("component", identity_type, name));
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Modifing service-discovery info: " + item.toString());
		}
		serviceEntity.addItems(item);
	}
}


//~ Formatted in Tigase Code Convention on 13/08/28
