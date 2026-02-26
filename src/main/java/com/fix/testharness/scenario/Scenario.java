package com.fix.testharness.scenario;

import java.util.List;

/**
 * Top-level scenario loaded from a YAML file.
 *
 * <p>Example YAML:
 * <pre>
 * name: "Basic Order Flow"
 * description: "Sends limit orders, cancels, and replaces"
 * steps:
 *   - type: new_order
 *     ...
 * </pre>
 */
public class Scenario {

    private String name;
    private String description;
    private List<ScenarioStep> steps;

    // ── Getters & Setters (required by SnakeYAML) ─────────────────────────

    public String getName()                  { return name; }
    public void setName(String name)         { this.name = name; }

    public String getDescription()                   { return description; }
    public void setDescription(String description)   { this.description = description; }

    public List<ScenarioStep> getSteps()             { return steps; }
    public void setSteps(List<ScenarioStep> steps)   { this.steps = steps; }

    @Override
    public String toString() {
        return "Scenario{name='" + name + "', steps=" + (steps == null ? 0 : steps.size()) + "}";
    }
}
