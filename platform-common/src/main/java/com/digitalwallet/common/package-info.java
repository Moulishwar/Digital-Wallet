/**
 * Cross-cutting building blocks shared by every service.
 *
 * <p>Scope is deliberately narrow — the money value type, error handling, and security
 * configuration. Domain classes, DTOs, and JPA entities are <em>not</em> shared: each service
 * defines its own view of the world. See DESIGN.md section 11 for the reasoning.
 */
package com.digitalwallet.common;
