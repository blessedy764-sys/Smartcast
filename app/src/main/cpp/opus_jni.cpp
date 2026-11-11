#include <jni.h>
#include <opus.h>
#include <vector>

extern "C" JNIEXPORT jlong JNICALL
Java_com_gitco_smartcast_audio_OpusCodec_nativeCreateEncoder(
        JNIEnv* env, jobject, jint sampleRate, jint channels, jint bitrate) {
    int error;
    OpusEncoder* encoder = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_VOIP, &error);
    if (error != OPUS_OK || !encoder) return 0L;

    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));          // e.g. 24000 (24 kbps)
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(5));             // Good balance for mobile CPU
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1));             // Embed backup of previous frame
    opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(15));      // Hint 15% Wi-Fi multicast loss
    opus_encoder_ctl(encoder, OPUS_SET_DTX(1));                    // Save airtime during pauses

    return reinterpret_cast<jlong>(encoder);
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_gitco_smartcast_audio_OpusCodec_nativeDecodeFrame(
        JNIEnv* env, jobject, jlong decoderPtr, jbyteArray encodedData, jint frameSize, jboolean decodeFec) {
    auto* decoder = reinterpret_cast<OpusDecoder*>(decoderPtr);
    if (!decoder) return nullptr;

    std::vector<opus_int16> pcmOut(frameSize);
    int decodedSamples = 0;

    if (encodedData == nullptr) {
        // Packet missing -> Trigger Opus Packet Loss Concealment (PLC)
        decodedSamples = opus_decode(decoder, nullptr, 0, pcmOut.data(), frameSize, 0);
    } else {
        jbyte* inputBytes = env->GetByteArrayElements(encodedData, nullptr);
        jsize inputLen = env->GetArrayLength(encodedData);

        // decodeFec = 1 extracts the previous frame's backup if the next packet arrived
        decodedSamples = opus_decode(
                decoder,
                reinterpret_cast<const unsigned char*>(inputBytes),
                inputLen,
                pcmOut.data(),
                frameSize,
                decodeFec ? 1 : 0
        );
        env->ReleaseByteArrayElements(encodedData, inputBytes, JNI_ABORT);
    }

    if (decodedSamples <= 0) return nullptr;

    jshortArray result = env->NewShortArray(decodedSamples);
    env->SetShortArrayRegion(result, 0, decodedSamples, pcmOut.data());
    return result;
}
