package com.zxl.zxlaiagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Trusted local operation. An explicit manifest is required for every invocation. */
public final class ImportLegacyHistoryCommand {
    private static final Set<String> OPTIONS = Set.of("--manifest", "--source-dir", "--backup-dir");
    private ImportLegacyHistoryCommand() {}

    static int run(String[] args, Supplier<SecurityDatabase> openDatabase, PrintWriter out) {
        try {
            var options = parse(args);
            var mapper = new ObjectMapper().findAndRegisterModules();
            var loaded = LegacyHistoryManifest.load(options.get("--manifest"), mapper);
            try (var db = openDatabase.get()) {
                var importer = new LegacyHistoryImporter(db, options.get("--source-dir"), options.get("--backup-dir"), mapper);
                out.println(importer.importHistory(loaded));
            }
            return 0;
        } catch (IllegalArgumentException invalid) {
            out.println("Invalid input. Required: --manifest PATH --source-dir PATH --backup-dir PATH.");
            return 2;
        } catch (SecurityFailure denied) {
            out.println("Import not confirmed: " + denied.code());
            return 1;
        } catch (RuntimeException failure) {
            out.println("Import not confirmed. Check source files, backups, and PostgreSQL; retry with the same manifest after resolving the failure.");
            return 1;
        } finally {
            out.flush();
        }
    }

    private static Map<String, Path> parse(String[] args) {
        if (args == null || args.length != 6) throw new IllegalArgumentException();
        var parsed = new HashMap<String, Path>();
        for (int i = 0; i < args.length; i += 2) {
            if (!OPTIONS.contains(args[i]) || args[i + 1].isBlank()
                    || parsed.putIfAbsent(args[i], Path.of(args[i + 1])) != null)
                throw new IllegalArgumentException();
        }
        return parsed;
    }

    public static void main(String[] args) {
        int result = run(args, SecurityDatabase::fromEnvironment, new PrintWriter(System.out));
        if (result != 0) System.exit(result);
    }
}
