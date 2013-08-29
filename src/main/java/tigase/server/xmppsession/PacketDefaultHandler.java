/*
 * PacketDefaultHandler.java
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



package tigase.server.xmppsession;

//~--- non-JDK imports --------------------------------------------------------

import tigase.db.NonAuthUserRepository;

import tigase.server.Iq;
import tigase.server.Packet;

import tigase.xmpp.Authorization;
import tigase.xmpp.BareJID;
import tigase.xmpp.JID;
import tigase.xmpp.NotAuthorizedException;
import tigase.xmpp.PacketErrorTypeException;
import tigase.xmpp.StanzaType;
import tigase.xmpp.XMPPException;
import tigase.xmpp.XMPPResourceConnection;
import tigase.xmpp.XMPPSession;

//~--- JDK imports ------------------------------------------------------------

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Queue;

/**
 * Describe class PacketDefaultHandler here.
 *
 *
 * Created: Fri Feb 2 15:08:58 2007
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class PacketDefaultHandler {
	/**
	 * Variable <code>log</code> is a class logger.
	 */
	private static final Logger log = Logger.getLogger(PacketDefaultHandler.class
			.getName());

	//~--- fields ---------------------------------------------------------------

	// private static TigaseRuntime runtime = TigaseRuntime.getTigaseRuntime();
	// private RosterAbstract roster_util =
	// RosterFactory.getRosterImplementation(true);
	private String[]     AUTH_ONLY_ELEMS = { "message", "presence" };
	private String[]     COMPRESS_PATH   = { "compress" };
	private String[]     IGNORE_PACKETS  = { "stream:features" };
	private StanzaType[] IGNORE_TYPES    = { StanzaType.error };

	//~--- constructors ---------------------------------------------------------

	/**
	 * Creates a new <code>PacketDefaultHandler</code> instance.
	 *
	 */
	public PacketDefaultHandler() {}

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param session
	 *
	 *
	 *
	 * @return a value of boolean
	 */
	public boolean canHandle(Packet packet, XMPPResourceConnection session) {
		if (session == null) {
			return false;
		}    // end of if (session == null)

		// Cannot forward packet if there is no destination address
		if (packet.getStanzaTo() == null) {

			// If this is simple <iq type="result"/> then ignore it
			// and consider it OK
			if ((packet.getElemName() == "iq") && (packet.getType() == StanzaType.result)) {

				// Nothing to do....
				return true;
			}
			if (log.isLoggable(Level.INFO)) {
				log.log(Level.INFO, "No ''to'' address, can''t deliver packet: {0}", packet);
			}

			return false;
		}

		return true;
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param session
	 * @param repo
	 * @param results
	 *
	 *
	 *
	 * @return a value of boolean
	 */
	public boolean forward(final Packet packet, final XMPPResourceConnection session,
			final NonAuthUserRepository repo, final Queue<Packet> results) {

		// Processing of the packets which needs to be processed as quickly
		// as possible, direct presences from unsubscribed entities apparently
		// have high priority as they may come from MUC and must be delivered
		// before room history
		// if (packet.getElemName() == "presence") {
		// PresenceType pres_type = roster_util.getPresenceType(session, packet);
		// if ((prese_type == PresenceType.in_initial)
		// && (packet.getElemFrom() != null)
		// && (roster_util.isSubscribedTo(session, packet.getElemFrom())
		// || (DynamicRoster.getBuddyItem(session, settings,
		// packet.getElemFrom()) != null))) {
		// }
		// }
		return false;
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param session
	 * @param repo
	 * @param results
	 *
	 *
	 *
	 * @return a value of boolean
	 */
	public boolean preprocess(final Packet packet, final XMPPResourceConnection session,
			final NonAuthUserRepository repo, final Queue<Packet> results) {
		if (session != null) {
			session.incPacketsCounter();

			XMPPSession parent = session.getParentSession();

			if (parent != null) {
				parent.incPacketsCounter();
			}
		}
		for (int i = 0; i < IGNORE_PACKETS.length; i++) {
			if ((packet.getElemName() == IGNORE_PACKETS[i]) && (packet.getType() ==
					IGNORE_TYPES[i])) {
				return true;
			}
		}
		if ((session == null) || session.isServerSession()) {
			return false;
		}    // end of if (session == null)
		try {

			// For all messages coming from the owner of this account set
			// proper 'from' attribute. This is actually needed for the case
			// when the user sends a message to himself.
			if (session.getConnectionId().equals(packet.getPacketFrom())) {
				if (!session.isAuthorized()) {

					// We allow only certain packets here...
					// For now it is simpler to disallow all messages and presences
					// packets, the rest should be bounced back anyway
					for (String elem : AUTH_ONLY_ELEMS) {
						if (packet.getElemName() == elem) {
							results.offer(Authorization.NOT_AUTHORIZED.getResponseMessage(packet,
									"You must authenticate session first, before you" +
									" can send any message or presence packet.", true));
							if (log.isLoggable(Level.FINE)) {
								log.log(Level.FINE,
										"Packet received before the session has been authenticated." +
										"Session details: connectionId=" + "{0}, sessionId={1}, packet={2}",
										new Object[] { session.getConnectionId(),
										session.getSessionId(), packet.toStringSecure() });
							}

							return true;
						}
					}

					return false;
				}

				// After authentication we require resource binding packet and
				// nothing else:
				// actually according to XEP-0170:
				// http://xmpp.org/extensions/xep-0170.html
				// stream compression might occur between authentication and resource
				// binding
				if (session.isResourceSet() || packet.isXMLNSStaticStr(Iq.IQ_BIND_PATH,
						"urn:ietf:params:xml:ns:xmpp-bind") || packet.isXMLNSStaticStr(COMPRESS_PATH,
						"http://jabber.org/protocol/compress")) {
					JID from_jid = session.getJID();

					if (from_jid != null) {

						// Do not replace current settings if there is at least correct
						// BareJID
						// already set.
						if ((packet.getStanzaFrom() == null) ||!from_jid.getBareJID().equals(packet
								.getStanzaFrom().getBareJID())) {
							if (log.isLoggable(Level.FINEST)) {
								log.log(Level.FINEST, "Setting correct from attribute: {0}", from_jid);
							}

							// No need for the line below, initVars(...) takes care of that
							// packet.getElement().setAttribute("from", from_jid.toString());
							packet.initVars(from_jid, packet.getStanzaTo());
						} else {
							if (log.isLoggable(Level.FINEST)) {
								log.log(Level.FINEST,
										"Skipping setting correct from attribute: {0}, is already correct.",
										from_jid);
							}
						}
					} else {
						log.log(Level.WARNING,
								"Session is authenticated but session.getJid() is empty: {0}", packet
								.toStringSecure());
					}
				} else {

					// We do not accept anything without resource binding....
					results.offer(Authorization.NOT_AUTHORIZED.getResponseMessage(packet,
							"You must bind the resource first: " +
							"http://www.xmpp.org/rfcs/rfc3920.html#bind", true));
					if (log.isLoggable(Level.INFO)) {
						log.log(Level.INFO, "Session details: connectionId={0}, sessionId={1}",
								new Object[] { session.getConnectionId(),
								session.getSessionId() });
					}
					if (log.isLoggable(Level.FINEST)) {
						log.log(Level.FINEST, "Session more detais: JID={0}", session.getjid());
					}

					return true;
				}
			}
		} catch (PacketErrorTypeException e) {

			// Ignore this packet
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST,
						"Ignoring packet with an error to non-existen user session: {0}", packet
						.toStringSecure());
			}
		} catch (Exception e) {
			log.log(Level.FINEST, "Packet preprocessing exception: ", e);

			return false;
		}    // end of try-catch

		return false;
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param session
	 * @param repo
	 * @param results
	 *
	 *
	 * @throws XMPPException
	 */
	public void process(Packet packet, XMPPResourceConnection session,
			NonAuthUserRepository repo, Queue<Packet> results)
					throws XMPPException {
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "Processing packet: {0}", packet.toStringSecure());
		}
		try {
			JID to = packet.getStanzaTo();

			// If this is simple <iq type="result"/> then ignore it
			// and consider it OK
			if ((to == null) && (packet.getElemName() == "iq") && (packet.getType() ==
					StanzaType.result)) {

				// Nothing to do....
				return;
			}
			if (session.isUserId(to.getBareJID())) {

				// Yes this is message to 'this' client
				Packet result;

				// This is where and how we set the address of the component
				// which should rceive the result packet for the final delivery
				// to the end-user. In most cases this is a c2s or Bosh component
				// which keep the user connection.
				String resource = packet.getStanzaTo().getResource();

				if (resource == null) {

					// In default packet handler we deliver packets to a specific resource only
					result = Authorization.FEATURE_NOT_IMPLEMENTED.getResponseMessage(packet,
							"The feature is not supported yet.", true);
					result.setPacketFrom(null);
					result.setPacketTo(null);
				} else {

					// Otherwise only to the given resource or sent back as error.
					XMPPResourceConnection con = session.getParentSession().getResourceForResource(
							resource);

					if (con != null) {
						result = packet.copyElementOnly();
						result.setPacketTo(con.getConnectionId());

						// In most cases this might be skept, however if there is a
						// problem during packet delivery an error might be sent back
						result.setPacketFrom(packet.getTo());
						if (log.isLoggable(Level.FINEST)) {
							log.log(Level.FINEST, "Delivering message, packet: {0}, to session: {1}",
									new Object[] { packet,
									con });
						}
					} else {
						result = Authorization.RECIPIENT_UNAVAILABLE.getResponseMessage(packet,
								"The recipient is no longer available.", true);
						result.setPacketFrom(null);
						result.setPacketTo(null);
					}
				}

				// Don't forget to add the packet to the results queue or it
				// will be lost.
				results.offer(result);

				return;
			}    // end of else
			if (packet.getStanzaFrom() != null) {
				BareJID from = packet.getStanzaFrom().getBareJID();

				if (session.isUserId(from)) {
					Packet result = packet.copyElementOnly();

					results.offer(result);
				}
			}
		} catch (NotAuthorizedException e) {
			try {
				results.offer(Authorization.NOT_AUTHORIZED.getResponseMessage(packet,
						"You must authorize session first.", true));
				log.log(Level.INFO, "NotAuthorizedException for packet: {0}", packet.toString());
			} catch (PacketErrorTypeException e2) {
				log.log(Level.INFO, "Packet processing exception: {0}", e2);
			}
		}    // end of try-catch
	}
}


//~ Formatted in Tigase Code Convention on 13/08/28
