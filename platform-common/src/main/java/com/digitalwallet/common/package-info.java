/**
 * Cross-cutting building blocks shared by every service.
 *
 * <p>Scope is deliberately narrow: the money value type, error handling, and security
 * configuration. Domain classes, DTOs, and JPA entities are <em>not</em> shared: each service
 * defines its own view of the world, so a change to one service's model cannot ripple into the
 * others through a shared jar.
 */
package com.digitalwallet.common;
