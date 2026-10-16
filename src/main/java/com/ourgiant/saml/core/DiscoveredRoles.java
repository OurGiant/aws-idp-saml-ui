package com.ourgiant.saml.core;

import java.util.List;
import java.util.Map;

/** The result of an Import-from-IdP login (#194): the assertion's roles, plus account aliases when AWS showed them (#244). */
public record DiscoveredRoles(List<SamlRole> roles, Map<String, String> accountAliases) {

    public DiscoveredRoles {
        roles = List.copyOf(roles);
        accountAliases = Map.copyOf(accountAliases);
    }
}
