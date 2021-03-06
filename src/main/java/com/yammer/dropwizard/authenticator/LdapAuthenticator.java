package com.yammer.dropwizard.authenticator;

import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.ImmutableSet;
import io.dropwizard.auth.basic.BasicCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.util.Hashtable;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

public class LdapAuthenticator {
    private static final Logger LOG = LoggerFactory.getLogger(LdapAuthenticator.class);
    protected final LdapConfiguration configuration;

    public LdapAuthenticator(LdapConfiguration configuration) {
        this.configuration = checkNotNull(configuration);
    }

    private static String sanitizeEntity(String name) {
        return name.replaceAll("[^A-Za-z0-9-_.]", "");
    }

    public boolean canAuthenticate() {
        try {
            new AutoclosingLdapContext(contextConfiguration(),
                    configuration.isNegotiateTls()).close();
            return true;
        } catch (Exception err) {
            //can't authenticate
        }
        return false;
    }

    private boolean filterByGroup(AutoclosingLdapContext context, String sanitizedUsername) throws NamingException {
        final Set<String> restrictedToGroups = configuration.getRestrictToGroups();
        if (restrictedToGroups.isEmpty()) {
            return true;
        }

        final StringBuilder groupFilter = new StringBuilder();
        for (String group : restrictedToGroups) {
            final String sanitizedGroup = sanitizeEntity(group);
            groupFilter.append(String.format("(%s=%s)", configuration.getGroupNameAttribute(), sanitizedGroup));
        }

        final String filter = String.format("(&(%s=%s)(|%s))", configuration.getGroupMembershipAttribute(), sanitizedUsername, groupFilter.toString());

        final NamingEnumeration<SearchResult> result = context.search(configuration.getGroupFilter(), filter, new SearchControls());
        try {
            return result.hasMore();
        } finally {
            result.close();
        }
    }

    private Set<String> getGroupMembershipsIntersectingWithRestrictedGroups(AutoclosingLdapContext context, String userName) throws NamingException {

        userName = userNameBaseOnGroupClass(userName);

        final String filter = String.format("(&(%s=%s)(objectClass=%s))", configuration.getGroupMembershipAttribute(), userName, configuration.getGroupClassName());
        final NamingEnumeration<SearchResult> result = context.search(configuration.getGroupFilter(), filter, new SearchControls());

        ImmutableSet.Builder<String> overlappingGroups = ImmutableSet.builder();
        try {
            while (result.hasMore()) {
                SearchResult next = result.next();
                if (next.getAttributes() != null && next.getAttributes().get(configuration.getGroupNameAttribute()) != null) {
                    String group = (String) next.getAttributes().get(configuration.getGroupNameAttribute()).get(0);
                    if (configuration.getRestrictToGroups().isEmpty() ||
                            configuration.getRestrictToGroups().contains(group)) {
                        overlappingGroups.add(group);
                    }
                }
            }
            return overlappingGroups.build();
        } finally {
            result.close();
        }
    }

    private String userNameBaseOnGroupClass(String userName) {
        if ("groupOfNames".equalsIgnoreCase(configuration.getGroupClassName())
                && "member".equalsIgnoreCase(configuration.getGroupMembershipAttribute())) {
            return toUserDN(userName);
        }
        return userName;
    }

    @Timed
    public boolean authenticate(BasicCredentials credentials) throws io.dropwizard.auth.AuthenticationException {
        final String sanitizedUsername = sanitizeEntity(credentials.getUsername());
        try {
            try (AutoclosingLdapContext context = buildContext(sanitizedUsername, credentials.getPassword())) {
                return filterByGroup(context, sanitizedUsername);
            }
        } catch (AuthenticationException ae) {
            LOG.debug("{} failed to authenticate. {}", sanitizedUsername, ae);
        } catch (NamingException err) {
            throw new io.dropwizard.auth.AuthenticationException(String.format("LDAP Authentication failure (username: %s)",
                    sanitizedUsername), err);
        }
        return false;
    }

    private AutoclosingLdapContext buildContext(String sanitizedUsername, String password) throws NamingException {
        final String userDN = toUserDN(sanitizedUsername);

        final Hashtable<String, String> env = contextConfiguration();

        env.put(Context.SECURITY_PRINCIPAL, userDN);
        env.put(Context.SECURITY_CREDENTIALS, password);

        return new AutoclosingLdapContext(env, configuration.isNegotiateTls());
    }

    private String toUserDN(String username) {
        return String.format("%s=%s,%s", configuration.getUserNameAttribute(), username, configuration.getUserFilter());
    }

    @Timed
    public Optional<User> authenticateAndReturnPermittedGroups(BasicCredentials credentials) throws io.dropwizard.auth.AuthenticationException {
        final String sanitizedUsername = sanitizeEntity(credentials.getUsername());
        try {
            try (AutoclosingLdapContext context = buildContext(sanitizedUsername, credentials.getPassword())) {
                Set<String> groupMemberships = getGroupMembershipsIntersectingWithRestrictedGroups(context, sanitizedUsername);
                if (!groupMemberships.isEmpty()) {
                    return Optional.of(new User(sanitizedUsername, groupMemberships));
                }
            }
        } catch (AuthenticationException ae) {
            LOG.debug("{} failed to authenticate. {}", sanitizedUsername, ae);
        } catch (NamingException err) {
            throw new io.dropwizard.auth.AuthenticationException(String.format("LDAP Authentication failure (username: %s)",
                    sanitizedUsername), err);
        }
        return Optional.empty();
    }

    private Hashtable<String, String> contextConfiguration() {
        final Hashtable<String, String> env = new Hashtable<>();

        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, configuration.getUri().toString());
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(configuration.getConnectTimeout().toMilliseconds()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(configuration.getReadTimeout().toMilliseconds()));
        env.put("com.sun.jndi.ldap.connect.pool", "true");

        return env;
    }
}