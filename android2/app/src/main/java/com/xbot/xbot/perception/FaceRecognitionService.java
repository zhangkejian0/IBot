package com.xbot.xbot.perception;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.xbot.xbot.data.PersonEntity;
import com.xbot.xbot.data.RoomConverters;
import com.xbot.xbot.model.IdentityMatch;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MobileFaceNet TFLite face embedding and cosine-similarity identification.
 *
 * <p>Ported from Flutter {@code lib/services/face_recognition_service.dart}.
 */
public class FaceRecognitionService {
    private static final String TAG = "FaceRecognition";
    private static final String MODEL_ASSET = "models/mobilefacenet.tflite";
    private static final int INPUT_SIZE = 112;

    private final Context appContext;
    private Interpreter interpreter;
    private int embeddingSize = 192;
    private boolean available;
    private String statusMessage;
    private int[] outputShape = new int[]{1, 192};

    private final float[] inputBuffer = new float[INPUT_SIZE * INPUT_SIZE * 3];
    private final float[] outputBuffer = new float[192];

    /** Cosine similarity match threshold (L2-normalized embeddings). */
    public double matchThreshold = 0.62;

    public FaceRecognitionService(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public boolean isAvailable() {
        return available;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public int getEmbeddingSize() {
        return embeddingSize;
    }

    public void initialize() {
        if (available) {
            return;
        }
        try {
            MappedByteBuffer model = loadModelFile(MODEL_ASSET);
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            interpreter = new Interpreter(model, options);

            int[] inShape = interpreter.getInputTensor(0).shape();
            int[] expected = new int[]{1, INPUT_SIZE, INPUT_SIZE, 3};
            if (!Arrays.equals(inShape, expected)) {
                markUnavailable("Model input shape mismatch: got " + Arrays.toString(inShape));
                return;
            }

            outputShape = interpreter.getOutputTensor(0).shape();
            embeddingSize = 1;
            for (int dim : outputShape) {
                if (dim > 0) {
                    embeddingSize *= dim;
                }
            }
            if (embeddingSize <= 0) {
                embeddingSize = 192;
            }

            available = true;
            statusMessage = "Face recognition loaded (embedding dim " + embeddingSize + ")";
            Log.i(TAG, statusMessage + " in=" + Arrays.toString(inShape) + " out=" + Arrays.toString(outputShape));
        } catch (Exception e) {
            markUnavailable("Face recognition model load failed: " + e.getMessage());
            Log.w(TAG, statusMessage, e);
        }
    }

    /**
     * Embed a cropped face bitmap. Returns L2-normalized vector or null on failure.
     */
    public List<Double> embed(Bitmap faceCrop) {
        if (!available || interpreter == null || faceCrop == null) {
            return null;
        }
        try {
            Bitmap resized = Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true);
            if (resized != faceCrop) {
                faceCrop.recycle();
            }

            int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
            resized.recycle();

            int bufIdx = 0;
            for (int pixel : pixels) {
                float r = ((pixel >> 16) & 0xFF);
                float g = ((pixel >> 8) & 0xFF);
                float b = (pixel & 0xFF);
                inputBuffer[bufIdx++] = (r - 127.5f) / 128.0f;
                inputBuffer[bufIdx++] = (g - 127.5f) / 128.0f;
                inputBuffer[bufIdx++] = (b - 127.5f) / 128.0f;
            }

            Arrays.fill(outputBuffer, 0f);
            Object input = wrapInput4d(inputBuffer);
            Object output = wrapOutput(outputShape, outputBuffer);
            interpreter.run(input, output);
            flattenOutput(output, outputBuffer);

            return l2Normalize(outputBuffer, embeddingSize);
        } catch (Exception e) {
            Log.w(TAG, "embed error: " + e.getMessage());
            return null;
        }
    }

    public IdentityMatch identify(List<Double> embedding, List<PersonEntity> people) {
        if (embedding == null || people == null || people.isEmpty()) {
            return null;
        }
        PersonEntity best = null;
        double bestSim = -1;
        for (PersonEntity person : people) {
            List<List<Double>> samples = RoomConverters.embeddingsFromJson(person.embeddingsJson);
            for (List<Double> sample : samples) {
                double sim = cosine(embedding, sample);
                if (sim > bestSim) {
                    bestSim = sim;
                    best = person;
                }
            }
        }
        if (best == null || bestSim < matchThreshold) {
            return null;
        }
        return new IdentityMatch(best, bestSim);
    }

    public void dispose() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        available = false;
    }

    private MappedByteBuffer loadModelFile(String assetPath) throws IOException {
        android.content.res.AssetFileDescriptor fd = appContext.getAssets().openFd(assetPath);
        FileInputStream inputStream = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = inputStream.getChannel();
        return channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.getStartOffset(),
                fd.getDeclaredLength());
    }

    private void markUnavailable(String message) {
        available = false;
        statusMessage = message;
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }

    private static Object wrapInput4d(float[] flat) {
        float[][][][] input = new float[1][INPUT_SIZE][INPUT_SIZE][3];
        int idx = 0;
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                input[0][y][x][0] = flat[idx++];
                input[0][y][x][1] = flat[idx++];
                input[0][y][x][2] = flat[idx++];
            }
        }
        return input;
    }

    private static Object wrapOutput(int[] shape, float[] flat) {
        if (shape.length == 2 && shape[0] == 1) {
            float[][] out = new float[shape[0]][shape[1]];
            System.arraycopy(flat, 0, out[0], 0, Math.min(shape[1], flat.length));
            return out;
        }
        if (shape.length == 4) {
            float[][][][] out = new float[shape[0]][shape[1]][shape[2]][shape[3]];
            int idx = 0;
            for (int a = 0; a < shape[0]; a++) {
                for (int b = 0; b < shape[1]; b++) {
                    for (int c = 0; c < shape[2]; c++) {
                        for (int d = 0; d < shape[3]; d++) {
                            if (idx < flat.length) {
                                out[a][b][c][d] = flat[idx++];
                            }
                        }
                    }
                }
            }
            return out;
        }
        return flat;
    }

    private static void flattenOutput(Object output, float[] sink) {
        List<Float> values = new ArrayList<>();
        collect(output, values);
        for (int i = 0; i < values.size() && i < sink.length; i++) {
            sink[i] = values.get(i);
        }
    }

    private static void collect(Object node, List<Float> sink) {
        if (node instanceof float[]) {
            for (float v : (float[]) node) {
                sink.add(v);
            }
        } else if (node instanceof float[][]) {
            for (float[] row : (float[][]) node) {
                for (float v : row) {
                    sink.add(v);
                }
            }
        } else if (node instanceof float[][][]) {
            for (float[][] plane : (float[][][]) node) {
                for (float[] row : plane) {
                    for (float v : row) {
                        sink.add(v);
                    }
                }
            }
        } else if (node instanceof float[][][][]) {
            for (float[][][] cube : (float[][][][]) node) {
                for (float[][] plane : cube) {
                    for (float[] row : plane) {
                        for (float v : row) {
                            sink.add(v);
                        }
                    }
                }
            }
        }
    }

    private static List<Double> l2Normalize(float[] vector, int length) {
        double sum = 0;
        int n = Math.min(length, vector.length);
        for (int i = 0; i < n; i++) {
            sum += vector[i] * vector[i];
        }
        double norm = Math.sqrt(sum);
        List<Double> out = new ArrayList<>(n);
        if (norm == 0) {
            for (int i = 0; i < n; i++) {
                out.add((double) vector[i]);
            }
            return out;
        }
        for (int i = 0; i < n; i++) {
            out.add(vector[i] / norm);
        }
        return out;
    }

    private static double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return -1;
        }
        double dot = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
        }
        return dot;
    }
}
