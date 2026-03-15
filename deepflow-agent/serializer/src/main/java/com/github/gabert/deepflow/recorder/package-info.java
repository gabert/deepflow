/**
 * Binary trace recorder using CBOR serialization.
 *
 * <p>Parallel implementation to {@code com.github.gabert.deepflow.serializer},
 * replacing Gson text-based output with CBOR binary records via the
 * {@code com.github.gabert.deepflow.codec} module.
 *
 * <p>Architecture:
 * <pre>
 *   DeepFlowAdvice → TraceRecorder → RingBuffer → DrainThread → Destination
 *                         ↓
 *                    Codec (CBOR)
 * </pre>
 */
package com.github.gabert.deepflow.recorder;