package com.brainboosterlab.channel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.brainboosterlab.channel.content.GenerationProperties;
import com.brainboosterlab.channel.content.ArtworkProperties;

@SpringBootApplication
@EnableConfigurationProperties({GenerationProperties.class, ArtworkProperties.class})
public class PuzzlePopApplication {

    public static void main(String[] args) {
        loadLocalEnvironment();
        SpringApplication.run(PuzzlePopApplication.class, args);
    }

    /**
     * Lets the local studio use its ignored .env file no matter whether it is launched
     * from a terminal, IntelliJ, VS Code, or the packaged JAR. Existing shell and JVM
     * settings always win, and nothing from the file is logged.
     */
    static void loadLocalEnvironment() {
        Path dotenv = Path.of(".env");
        if (!Files.isRegularFile(dotenv)) return;
        try {
            for (String rawLine : Files.readAllLines(dotenv)) {
                String line = rawLine.trim();
                if (line.isBlank() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring("export ".length()).trim();
                int separator = line.indexOf('=');
                if (separator < 1) continue;
                String key = line.substring(0, separator).trim();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                if (System.getenv(key) != null || System.getProperty(key) != null) continue;
                String value = line.substring(separator + 1).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                System.setProperty(key, value);
            }
        } catch (IOException ignored) {
            // A local configuration convenience must never prevent a normal startup.
        }
    }
}
