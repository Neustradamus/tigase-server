package tigase.disteventbus.component.stores;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import tigase.kernel.beans.Bean;
import tigase.xmpp.JID;

@Bean(name = "affiliationStore")
public class AffiliationStore {

	private final Map<JID, Affiliation> affiliations = new ConcurrentHashMap<JID, Affiliation>();

	public Affiliation getAffiliation(final JID jid) {
		Affiliation a = affiliations.get(jid);
		return a == null ? Affiliation.none : a;
	}

	public void putAffiliation(JID jid, Affiliation affiliation) {
		this.affiliations.put(jid, affiliation);
	}

}
