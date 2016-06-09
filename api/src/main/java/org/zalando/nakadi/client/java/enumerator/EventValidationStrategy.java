package org.zalando.nakadi.client.java.enumerator;

import scala.Option;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Defines a rule for validation of an incoming Event. Rules might require
 * additional parameters; see the `doc` field of the existing rules for details.
 * See GET /registry/validation-strategies for a list of available rules.
 */
public enum EventValidationStrategy {
	SCHEMA_VALIDATION("schema-validation");
	private final String strategy;

	private EventValidationStrategy(String strategy) {
		this.strategy = strategy;
	}
	@JsonValue
	public String getStrategy() {
		return strategy;
	}

	public static Option<EventValidationStrategy> withName(String code) {
		for (EventValidationStrategy e : EventValidationStrategy.values()) {
			if (e.strategy.equals(code))
				return Option.apply(e);
		}
		return Option.empty();
	}
}
