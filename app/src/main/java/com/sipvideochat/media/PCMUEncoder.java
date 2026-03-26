package com.sipvideochat.media;

/**
 * μ-law (G.711) 编解码器
 * (从桌面端 src/media/PCMUEncoder.java 直接移植，纯Java实现)
 */
public class PCMUEncoder {

    private static final short[] BIAS = {0x84, 0x108, 0x210, 0x420, 0x840, 0x1080, 0x2100, 0x4200};
    private static final int CLIP = 32635;

    /**
     * 将 16bit PCM 转换为 8bit PCMU (G.711 μ-law)
     */
    public static byte[] encode(byte[] pcmData) {
        byte[] mulaw = new byte[pcmData.length / 2];

        for (int i = 0; i < mulaw.length; i++) {
            int sample = (pcmData[i * 2] & 0xff) | ((pcmData[i * 2 + 1] & 0xff) << 8);
            short pcm = (short) sample;
            mulaw[i] = linearToMulaw(pcm);
        }

        return mulaw;
    }

    /**
     * 将 8bit PCMU 转换为 16bit PCM
     */
    public static byte[] decode(byte[] mulawData) {
        byte[] pcm = new byte[mulawData.length * 2];

        for (int i = 0; i < mulawData.length; i++) {
            short sample = mulawToLinear(mulawData[i]);
            pcm[i * 2] = (byte) (sample & 0xff);
            pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
        }

        return pcm;
    }

    private static byte linearToMulaw(short pcm) {
        int sign = (pcm < 0) ? 0x80 : 0;
        int magnitude = Math.abs(pcm);

        if (magnitude > CLIP) {
            magnitude = CLIP;
        }

        magnitude += BIAS[0];

        int exponent = 7;
        for (int i = 0; i < 8; i++) {
            if (magnitude <= BIAS[i]) {
                exponent = i;
                break;
            }
        }

        int mantissa = (magnitude >> (exponent + 3)) & 0x0F;
        int mulaw = ~(sign | (exponent << 4) | mantissa);

        return (byte) (mulaw & 0xFF);
    }

    private static short mulawToLinear(byte mulaw) {
        mulaw = (byte) ~mulaw;

        int sign = (mulaw & 0x80);
        int exponent = (mulaw >> 4) & 0x07;
        int mantissa = mulaw & 0x0F;

        int sample = ((mantissa << 3) + BIAS[0]) << exponent;
        sample -= BIAS[0];

        return (short) (sign != 0 ? -sample : sample);
    }
}
