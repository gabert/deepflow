package com.github.gabert.arachna.trace.codec.envelope;

import com.github.gabert.arachna.trace.spi.jpaproxy.JpaProxyResolver;

/**
 * Holds the process-wide {@link JpaProxyResolver} used by
 * {@link EnvelopeSerializer} during encoding.
 *
 * <p>This hook is deliberately kept off the {@code Codec} facade: it is only
 * meaningful on the <em>encoding</em> side (the agent registers a resolver via
 * its SPI bootstrap), while decode-only consumers of the codec (renderer,
 * processor) should never see a mutable setter in their API surface.</p>
 *
 * <p>The slot is static because the codec's CBOR encoder is a static, shared
 * {@code ObjectMapper}; there is exactly one encoding pipeline per process.</p>
 */
public final class JpaProxyResolvers {

   private static volatile JpaProxyResolver active;

   private JpaProxyResolvers() {
   }

   /** Register the resolver to consult for every proxy encountered while encoding. */
   public static void setActive(JpaProxyResolver resolver) {
      active = resolver;
   }

   static JpaProxyResolver active() {
      return active;
   }
}
