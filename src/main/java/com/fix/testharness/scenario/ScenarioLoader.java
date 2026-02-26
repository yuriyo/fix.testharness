package com.fix.testharness.scenario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads a {@link Scenario} from a YAML file using SnakeYAML.
 */
public class ScenarioLoader {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLoader.class);

    private ScenarioLoader() {}

    /**
     * Load a scenario from the given file path.
     *
     * @param filePath path to the YAML scenario file
     * @return the parsed {@link Scenario}
     * @throws IOException if the file cannot be read
     */
    public static Scenario load(String filePath) throws IOException {
        log.info("Loading scenario from: {}", filePath);

        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);

        Yaml yaml = new Yaml(new Constructor(Scenario.class, opts));

        try (InputStream in = new FileInputStream(filePath)) {
            Scenario scenario = yaml.load(in);
            if (scenario == null) {
                throw new IllegalArgumentException("Scenario file is empty: " + filePath);
            }
            if (scenario.getSteps() == null || scenario.getSteps().isEmpty()) {
                throw new IllegalArgumentException("Scenario has no steps: " + filePath);
            }
            log.info("Loaded scenario '{}' with {} step(s)",
                     scenario.getName(), scenario.getSteps().size());
            return scenario;
        }
    }
}
