package com.zxl.zxlaiagent.security;

import java.io.PrintWriter;
import java.util.*;

/** Local administrator entry only. No HTTP registration or password command-line argument. */
public final class ProvisionAccountCommand {
    private ProvisionAccountCommand() {}
    @FunctionalInterface interface SecretInput { char[] read() throws Exception; }
    @FunctionalInterface interface Factory { Port open() throws Exception; }
    interface Port extends AutoCloseable {
        void provision(String subject, String login, String password, String tenant, String role);
        void close();
    }
    static Port databasePort(SecurityDatabase db) {
        final AuthenticationService auth;
        try { auth = new AuthenticationService(db); }
        catch (RuntimeException | Error failure) { db.close(); throw failure; }
        return new Port() {
            public void provision(String s,String l,String p,String t,String r) { auth.provision(s,l,p,t,r); }
            public void close() { db.close(); }
        };
    }
    static int run(String[] args, SecretInput input, Factory factory, PrintWriter out) {
        char[] password = null;
        try {
            Map<String,String> options = parse(args);
            password = input.read();
            if (password == null || password.length < 12 || !AuthenticationService.validPassword(new String(password))) {
                out.println("Password must contain at least 12 characters and at most 72 UTF-8 bytes."); return 2;
            }
            try (Port port = factory.open()) {
                port.provision(options.get("--subject"), options.get("--login"), new String(password),
                        options.get("--tenant"), options.get("--role"));
            }
            out.println("Account created. Existing accounts are never overwritten."); return 0;
        } catch (IllegalArgumentException invalid) {
            out.println("Invalid input. Required: --subject ID --login NAME --tenant ID --role employee|knowledge-maintainer. Password is read from the console."); return 2;
        } catch (Exception failure) {
            // Driver messages can include deployment details. Do not print the exception or inputs.
            out.println("Account was not confirmed created. Check database availability or an existing account; no overwrite is performed."); return 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            out.flush();
        }
    }
    private static Map<String,String> parse(String[] args) {
        Set<String> keys = Set.of("--subject","--login","--tenant","--role");
        if (args == null || args.length != 8) throw new IllegalArgumentException();
        Map<String,String> options = new HashMap<>();
        for (int i=0;i<args.length;i+=2) {
            if (!keys.contains(args[i]) || !AuthenticationService.validId(args[i+1])
                    || options.putIfAbsent(args[i],args[i+1]) != null) throw new IllegalArgumentException();
        }
        if (!options.keySet().equals(keys) || !Set.of("employee","knowledge-maintainer").contains(options.get("--role")))
            throw new IllegalArgumentException();
        return options;
    }
    public static void main(String[] args) {
        var out = new PrintWriter(System.out);
        int result = run(args, () -> {
            var console = System.console();
            if (console == null) throw new IllegalArgumentException("Interactive console required");
            return console.readPassword("New account password: ");
        }, () -> databasePort(SecurityDatabase.pooledFromEnvironment()), out);
        if (result != 0) System.exit(result);
    }
}
