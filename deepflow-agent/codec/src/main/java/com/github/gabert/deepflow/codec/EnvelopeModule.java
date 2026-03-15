package com.github.gabert.deepflow.codec;

import com.fasterxml.jackson.databind.module.SimpleModule;

// ─────────────────────────────────────────────────────────────
// EnvelopeModule
//
// Jackson SimpleModule that installs EnvelopeModifier into the
// ObjectMapper. Register this module on any ObjectMapper
// (JSON or CBOR) to enable envelope wrapping.
// ─────────────────────────────────────────────────────────────
public final class EnvelopeModule extends SimpleModule {

   public EnvelopeModule() {
      super("EnvelopeModule");
   }

   @Override
   public void setupModule(SetupContext context) {
      super.setupModule(context);
      context.addBeanSerializerModifier(new EnvelopeModifier());
   }
}
