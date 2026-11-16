package com.google.android.accessibility.utils.output;

import static android.media.AudioFormat.ENCODING_PCM_16BIT;
import static android.media.AudioFormat.ENCODING_PCM_32BIT;
import static android.media.AudioFormat.ENCODING_PCM_8BIT;

import android.media.AudioFormat;
import android.util.Log;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A utility class to load and parse WAV files. */
public class WavFileLoader {

  private static final String TAG = "WavFileLoader";

  // WAV header constants
  private static final int RIFF_HEADER_SIZE = 12;
  private static final String RIFF_CHUNK_ID = "RIFF";
  private static final String WAVE_FORMAT = "WAVE";
  private static final int CHUNK_HEADER_SIZE = 8;
  private static final String FMT_SUBCHUNK_ID = "fmt ";
  private static final int FMT_BODY_MIN_SIZE = 16;
  private static final String DATA_SUBCHUNK_ID = "data";
  private static final short PCM_AUDIO_FORMAT = 1;

  public static final int HEADER_SIZE = 44;

  /** Holds the parsed data from a WAV file. */
  public static class WavData {
    public final byte[] pcmData;
    public final int sampleRate;
    public final int channelConfig;
    public final int audioFormatEncoding;

    WavData(byte[] pcmData, int sampleRate, int channelConfig, int audioFormatEncoding) {
      this.pcmData = pcmData;
      this.sampleRate = sampleRate;
      this.channelConfig = channelConfig;
      this.audioFormatEncoding = audioFormatEncoding;
    }
  }

  private WavFileLoader() {}

  /**
   * Loads a WAV file, parses its header, and extracts the PCM data.
   *
   * @param wavFile The WAV file to load.
   * @return A {@link WavData} object containing the PCM data and format info, or {@code null} if
   *     loading fails.
   */
  @Nullable
  public static WavData loadWavFile(File wavFile) {
    if (wavFile == null || !wavFile.exists()) {
      Log.e(TAG, "Invalid WAV file or file does not exist.");
      return null;
    }

    try (InputStream inputStream = new FileInputStream(wavFile);
        ByteArrayOutputStream pcmOutputStream = new ByteArrayOutputStream()) {

      // --- WAV Header Parsing: walk the RIFF chunks, so files with extra chunks before the
      // data chunk and oversized fmt chunks are handled. ---
      byte[] riffHeader = new byte[RIFF_HEADER_SIZE];
      if (!readFully(inputStream, riffHeader)) {
        Log.e(TAG, "Could not read RIFF header from: " + wavFile.getName());
        return null;
      }
      if (!(new String(riffHeader, 0, 4).equals(RIFF_CHUNK_ID)
          && new String(riffHeader, 8, 4).equals(WAVE_FORMAT))) {
        Log.e(TAG, wavFile.getName() + " is not a valid WAV file (RIFF/WAVE tags missing).");
        return null;
      }

      short audioFormatCode = -1;
      short numChannels = 0;
      int currentSampleRate = 0;
      short bitsPerSample = 0;
      int dataChunkSize = -1;
      byte[] chunkHeader = new byte[CHUNK_HEADER_SIZE];
      while (readFully(inputStream, chunkHeader)) {
        int chunkSize =
            ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        String chunkId = new String(chunkHeader, 0, 4);
        if (chunkId.equals(FMT_SUBCHUNK_ID)) {
          if (chunkSize < FMT_BODY_MIN_SIZE) {
            Log.e(TAG, wavFile.getName() + ": WAV 'fmt ' chunk too small.");
            return null;
          }
          byte[] fmtBody = new byte[FMT_BODY_MIN_SIZE];
          if (!readFully(inputStream, fmtBody)) {
            Log.e(TAG, wavFile.getName() + ": truncated WAV 'fmt ' chunk.");
            return null;
          }
          ByteBuffer fmtBuffer = ByteBuffer.wrap(fmtBody).order(ByteOrder.LITTLE_ENDIAN);
          audioFormatCode = fmtBuffer.getShort(0);
          numChannels = fmtBuffer.getShort(2);
          currentSampleRate = fmtBuffer.getInt(4);
          bitsPerSample = fmtBuffer.getShort(14);
          if (!skipFully(inputStream, chunkSize - FMT_BODY_MIN_SIZE + (chunkSize % 2))) {
            Log.e(TAG, wavFile.getName() + ": truncated WAV 'fmt ' chunk.");
            return null;
          }
        } else if (chunkId.equals(DATA_SUBCHUNK_ID)) {
          if (audioFormatCode == -1) {
            Log.e(TAG, wavFile.getName() + ": WAV 'data' chunk before 'fmt ' chunk.");
            return null;
          }
          dataChunkSize = chunkSize;
          break;
        } else {
          if (!skipFully(inputStream, chunkSize + (chunkSize % 2))) {
            Log.e(TAG, wavFile.getName() + ": truncated WAV chunk.");
            return null;
          }
        }
      }
      if (dataChunkSize < 0) {
        Log.e(TAG, wavFile.getName() + ": WAV 'data' chunk not found.");
        return null;
      }

      if (audioFormatCode != PCM_AUDIO_FORMAT) {
        Log.e(
            TAG,
            "Unsupported WAV audio format code: "
                + audioFormatCode
                + " in "
                + wavFile.getName()
                + ". Only PCM is supported.");
        return null;
      }

      int currentChannelConfig;
      switch (numChannels) {
        case 1 -> currentChannelConfig = AudioFormat.CHANNEL_OUT_MONO;
        case 2 -> currentChannelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        default -> {
          Log.e(TAG, "Unsupported number of channels: " + numChannels + " in " + wavFile.getName());
          return null;
        }
      }

      int currentAudioFormatEncoding;
      switch (bitsPerSample) {
        case 8 -> currentAudioFormatEncoding = ENCODING_PCM_8BIT;
        case 16 -> currentAudioFormatEncoding = ENCODING_PCM_16BIT;
        case 32 -> currentAudioFormatEncoding = ENCODING_PCM_32BIT;
        default -> {
          Log.e(TAG, "Unsupported bits per sample: " + bitsPerSample + " in " + wavFile.getName());
          return null;
        }
      }
      // --- End WAV Header Parsing ---

      // --- Read PCM Data into ByteArrayOutputStream ---
      byte[] readBuffer = new byte[4096];
      int bytesReadFromPcm;
      int totalPcmBytesRead = 0;

      int limit = (dataChunkSize > 0) ? dataChunkSize : Integer.MAX_VALUE;

      while (totalPcmBytesRead < limit
          && (bytesReadFromPcm =
                  inputStream.read(
                      readBuffer, 0, Math.min(readBuffer.length, limit - totalPcmBytesRead)))
              != -1) {
        pcmOutputStream.write(readBuffer, 0, bytesReadFromPcm);
        totalPcmBytesRead += bytesReadFromPcm;
      }
      // --- End PCM Data Reading ---

      if (pcmOutputStream.size() == 0) {
        return null;
      }
      byte[] pcmData = pcmOutputStream.toByteArray();
      return new WavData(
          pcmData, currentSampleRate, currentChannelConfig, currentAudioFormatEncoding);

    } catch (FileNotFoundException e) {
      Log.e(TAG, "WAV File not found: " + wavFile.getAbsolutePath(), e);
    } catch (IOException e) {
      Log.e(TAG, "IOException during WAV loading: " + wavFile.getAbsolutePath(), e);
    } catch (RuntimeException e) { // Catch any other unexpected exceptions.
      Log.e(TAG, "Unexpected error loading WAV: " + wavFile.getAbsolutePath(), e);
    }
    return null;
  }

  private static boolean readFully(InputStream inputStream, byte[] buffer) throws IOException {
    int offset = 0;
    while (offset < buffer.length) {
      int bytesRead = inputStream.read(buffer, offset, buffer.length - offset);
      if (bytesRead == -1) {
        return false;
      }
      offset += bytesRead;
    }
    return true;
  }

  private static boolean skipFully(InputStream inputStream, long bytesToSkip) throws IOException {
    long remaining = bytesToSkip;
    while (remaining > 0) {
      long skipped = inputStream.skip(remaining);
      if (skipped <= 0) {
        return false;
      }
      remaining -= skipped;
    }
    return true;
  }
}
