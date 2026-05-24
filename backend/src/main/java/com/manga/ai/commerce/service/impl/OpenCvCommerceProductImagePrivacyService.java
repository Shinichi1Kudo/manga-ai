package com.manga.ai.commerce.service.impl;

import com.manga.ai.commerce.dto.CommerceProductCreateRequest;
import com.manga.ai.commerce.service.CommerceProductImagePrivacyService;
import com.manga.ai.common.service.OssService;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class OpenCvCommerceProductImagePrivacyService implements CommerceProductImagePrivacyService {

    private static final String PRIVACY_FOLDER = "commerce/product-privacy";
    private static final int DOWNLOAD_TIMEOUT_MILLIS = 15000;
    private static final String CASCADE_RESOURCE = "opencv/haarcascade_frontalface_alt.xml";

    private final OssService ossService;
    private final ImageDownloader imageDownloader;
    private final FaceDetector faceDetector;

    @Autowired
    public OpenCvCommerceProductImagePrivacyService(OssService ossService) {
        this(ossService, new UrlImageDownloader(), new OpenCvHaarCascadeFaceDetector());
    }

    OpenCvCommerceProductImagePrivacyService(
            OssService ossService,
            ImageDownloader imageDownloader,
            FaceDetector faceDetector) {
        this.ossService = ossService;
        this.imageDownloader = imageDownloader;
        this.faceDetector = faceDetector;
    }

    @Override
    public List<CommerceProductCreateRequest.ProductImageItem> sanitizeProductReferences(
            List<CommerceProductCreateRequest.ProductImageItem> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        List<CommerceProductCreateRequest.ProductImageItem> sanitized = new ArrayList<>();
        for (CommerceProductCreateRequest.ProductImageItem reference : references) {
            sanitized.add(sanitizeReference(reference));
        }
        return sanitized;
    }

    private CommerceProductCreateRequest.ProductImageItem sanitizeReference(CommerceProductCreateRequest.ProductImageItem reference) {
        CommerceProductCreateRequest.ProductImageItem copy = copyReference(reference);
        if (reference == null || isBlank(reference.getUrl())) {
            return copy;
        }
        try {
            byte[] originalBytes = imageDownloader.download(reference.getUrl().trim());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (image == null) {
                return copy;
            }
            List<Rectangle> faces = faceDetector.detectFaces(image);
            if (faces == null || faces.isEmpty()) {
                faces = fallbackPrivacyAreas(reference, image);
            }
            if (faces.isEmpty()) {
                log.debug("商品参考图未检测到人脸，保留原图: usage={}, url={}", reference.getUsage(), reference.getUrl());
                return copy;
            }
            BufferedImage masked = maskFaces(image, faces);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(masked, "png", output);
            String safeUrl = ossService.uploadImage(output.toByteArray(), PRIVACY_FOLDER, "image/png", "png");
            if (!isBlank(safeUrl)) {
                copy.setUrl(safeUrl);
                log.info("商品参考图人脸脱敏完成: usage={}, faceAreas={}, safeUrl={}",
                        reference.getUsage(), faces.size(), safeUrl);
            }
        } catch (Exception e) {
            log.warn("商品参考图人脸脱敏失败，保留原图: usage={}, url={}", reference.getUsage(), reference.getUrl(), e);
        }
        return copy;
    }

    private List<Rectangle> fallbackPrivacyAreas(
            CommerceProductCreateRequest.ProductImageItem reference,
            BufferedImage image) {
        if (reference == null || image == null || !"上身参考".equals(reference.getUsage())) {
            return List.of();
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return List.of();
        }

        int areaWidth = Math.max(1, Math.round(width * 0.40f));
        int areaHeight = Math.max(1, Math.round(height * 0.45f));
        int x = Math.max(0, Math.round((width - areaWidth) / 2.0f));
        int y = 0;
        log.info("商品上身参考图未检测到人脸，启用头脸区域兜底脱敏: url={}", reference.getUrl());
        return List.of(new Rectangle(x, y, Math.min(areaWidth, width - x), Math.min(areaHeight, height)));
    }

    private CommerceProductCreateRequest.ProductImageItem copyReference(CommerceProductCreateRequest.ProductImageItem reference) {
        CommerceProductCreateRequest.ProductImageItem copy = new CommerceProductCreateRequest.ProductImageItem();
        if (reference != null) {
            copy.setUrl(reference.getUrl());
            copy.setUsage(reference.getUsage());
        }
        return copy;
    }

    private BufferedImage maskFaces(BufferedImage source, List<Rectangle> faces) {
        BufferedImage masked = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = masked.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
            graphics.setColor(new Color(28, 28, 28));
            for (Rectangle face : faces) {
                Rectangle area = expand(face, source.getWidth(), source.getHeight());
                graphics.fillRect(area.x, area.y, area.width, area.height);
            }
        } finally {
            graphics.dispose();
        }
        return masked;
    }

    private Rectangle expand(Rectangle face, int maxWidth, int maxHeight) {
        int expandX = Math.max(8, Math.round(face.width * 0.35f));
        int expandY = Math.max(8, Math.round(face.height * 0.45f));
        int x = Math.max(0, face.x - expandX);
        int y = Math.max(0, face.y - expandY);
        int right = Math.min(maxWidth, face.x + face.width + expandX);
        int bottom = Math.min(maxHeight, face.y + face.height + expandY);
        return new Rectangle(x, y, Math.max(1, right - x), Math.max(1, bottom - y));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @FunctionalInterface
    interface ImageDownloader {
        byte[] download(String url) throws Exception;
    }

    @FunctionalInterface
    interface FaceDetector {
        List<Rectangle> detectFaces(BufferedImage image);
    }

    private static class UrlImageDownloader implements ImageDownloader {
        @Override
        public byte[] download(String url) throws Exception {
            URLConnection connection = new URL(url).openConnection();
            connection.setConnectTimeout(DOWNLOAD_TIMEOUT_MILLIS);
            connection.setReadTimeout(DOWNLOAD_TIMEOUT_MILLIS);
            try (InputStream input = connection.getInputStream()) {
                return input.readAllBytes();
            }
        }
    }

    private static class OpenCvHaarCascadeFaceDetector implements FaceDetector {
        private final AtomicReference<CascadeClassifier> classifierRef = new AtomicReference<>();

        @Override
        public List<Rectangle> detectFaces(BufferedImage image) {
            CascadeClassifier classifier = classifier();
            if (classifier == null || image == null) {
                return List.of();
            }
            Mat mat = bufferedImageToMat(image);
            Mat gray = new Mat();
            RectVector faces = new RectVector();
            try {
                opencv_imgproc.cvtColor(mat, gray, opencv_imgproc.COLOR_BGR2GRAY);
                opencv_imgproc.equalizeHist(gray, gray);
                classifier.detectMultiScale(gray, faces);
                List<Rectangle> result = new ArrayList<>();
                for (long i = 0; i < faces.size(); i++) {
                    Rect face = faces.get(i);
                    result.add(new Rectangle(face.x(), face.y(), face.width(), face.height()));
                }
                return result;
            } finally {
                faces.close();
                gray.close();
                mat.close();
            }
        }

        private CascadeClassifier classifier() {
            CascadeClassifier cached = classifierRef.get();
            if (cached != null) {
                return cached.empty() ? null : cached;
            }
            CascadeClassifier classifier = loadClassifier();
            if (!classifierRef.compareAndSet(null, classifier)) {
                classifier.close();
            }
            CascadeClassifier current = classifierRef.get();
            return current == null || current.empty() ? null : current;
        }

        private CascadeClassifier loadClassifier() {
            try (InputStream input = OpenCvHaarCascadeFaceDetector.class.getClassLoader().getResourceAsStream(CASCADE_RESOURCE)) {
                if (input == null) {
                    log.warn("OpenCV 人脸检测模型不存在: {}", CASCADE_RESOURCE);
                    return new CascadeClassifier();
                }
                File tempFile = File.createTempFile("haarcascade_frontalface_alt", ".xml");
                tempFile.deleteOnExit();
                java.nio.file.Files.copy(input, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                CascadeClassifier classifier = new CascadeClassifier(tempFile.getAbsolutePath());
                if (classifier.empty()) {
                    log.warn("OpenCV 人脸检测模型加载失败: {}", CASCADE_RESOURCE);
                }
                return classifier;
            } catch (Exception e) {
                log.warn("OpenCV 人脸检测模型初始化失败", e);
                return new CascadeClassifier();
            }
        }

        private Mat bufferedImageToMat(BufferedImage image) {
            BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D graphics = converted.createGraphics();
            try {
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            byte[] pixels = ((java.awt.image.DataBufferByte) converted.getRaster().getDataBuffer()).getData();
            Mat mat = new Mat(converted.getHeight(), converted.getWidth(), org.bytedeco.opencv.global.opencv_core.CV_8UC3);
            mat.data().put(pixels);
            return mat;
        }
    }
}
