package io.quarkiverse.undertow.tck;

import java.security.Principal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.undertow.security.idm.Account;
import io.undertow.security.idm.Credential;
import io.undertow.security.idm.IdentityManager;
import io.undertow.security.idm.PasswordCredential;

/**
 * The identity store the Jakarta Servlet TCK assumes: {@code j2ee} is the authorised user and
 * {@code javajoe} the unauthorised one, each mapped to the roles the TCK's deployment descriptors
 * grant. No principal holds the VP role, which several tests rely on.
 */
public class TckIdentityManager implements IdentityManager {

    private record User(String password, Set<String> roles) {
    }

    private static final Map<String, User> USERS = new HashMap<>();

    static {
        USERS.put("j2ee", new User("j2ee", Set.of("Administrator", "Employee")));
        USERS.put("javajoe", new User("javajoe", Set.of("Manager", "Employee")));
    }

    @Override
    public Account verify(Account account) {
        // Already-verified account (e.g. from an established session).
        return account;
    }

    @Override
    public Account verify(String id, Credential credential) {
        User user = USERS.get(id);
        if (user == null || !(credential instanceof PasswordCredential password)) {
            return null;
        }
        if (Arrays.equals(user.password().toCharArray(), password.getPassword())) {
            return accountFor(id, user);
        }
        return null;
    }

    @Override
    public Account verify(Credential credential) {
        return null;
    }

    private static Account accountFor(String id, User user) {
        Principal principal = () -> id;
        return new Account() {
            @Override
            public Principal getPrincipal() {
                return principal;
            }

            @Override
            public Set<String> getRoles() {
                return user.roles();
            }
        };
    }
}
