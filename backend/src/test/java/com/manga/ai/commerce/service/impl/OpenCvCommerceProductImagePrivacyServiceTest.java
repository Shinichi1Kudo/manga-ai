package com.manga.ai.commerce.service.impl;

import com.manga.ai.commerce.dto.CommerceProductCreateRequest;
import com.manga.ai.common.service.OssService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenCvCommerceProductImagePrivacyServiceTest {

    @Test
    void sanitizeProductReferencesUploadsMaskedCopyWhenFaceIsDetected() throws Exception {
        OssService ossService = mock(OssService.class);
        when(ossService.uploadImage(any(byte[].class), eq("commerce/product-privacy"), eq("image/png"), eq("png")))
                .thenReturn("https://oss.example.com/safe-product.png");
        byte[] sourceImage = createPngWithRedFacePatch();
        OpenCvCommerceProductImagePrivacyService service = new OpenCvCommerceProductImagePrivacyService(
                ossService,
                url -> sourceImage,
                image -> List.of(new Rectangle(30, 20, 24, 24))
        );

        CommerceProductCreateRequest.ProductImageItem item = productImage("https://oss.example.com/worn.png", "上身参考");

        List<CommerceProductCreateRequest.ProductImageItem> result = service.sanitizeProductReferences(List.of(item));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUsage()).isEqualTo("上身参考");
        assertThat(result.get(0).getUrl()).isEqualTo("https://oss.example.com/safe-product.png");
        assertThat(item.getUrl()).isEqualTo("https://oss.example.com/worn.png");

        ArgumentCaptor<byte[]> imageCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ossService).uploadImage(imageCaptor.capture(), eq("commerce/product-privacy"), eq("image/png"), eq("png"));
        BufferedImage maskedImage = ImageIO.read(new java.io.ByteArrayInputStream(imageCaptor.getValue()));
        assertThat(maskedImage).isNotNull();
        assertThat(maskedImage.getRGB(48, 22)).isNotEqualTo(Color.RED.getRGB());
    }

    @Test
    void sanitizeProductReferencesKeepsOriginalUrlWhenNoFaceIsDetected() throws Exception {
        OssService ossService = mock(OssService.class);
        OpenCvCommerceProductImagePrivacyService service = new OpenCvCommerceProductImagePrivacyService(
                ossService,
                url -> createPngWithRedFacePatch(),
                image -> List.of()
        );

        CommerceProductCreateRequest.ProductImageItem item = productImage("https://oss.example.com/main.png", "主图");

        List<CommerceProductCreateRequest.ProductImageItem> result = service.sanitizeProductReferences(List.of(item));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUsage()).isEqualTo("主图");
        assertThat(result.get(0).getUrl()).isEqualTo("https://oss.example.com/main.png");
        verify(ossService, never()).uploadImage(any(byte[].class), any(), any(), any());
    }

    @Test
    void sanitizeProductReferencesMasksWornReferenceWhenFaceDetectorMisses() throws Exception {
        OssService ossService = mock(OssService.class);
        when(ossService.uploadImage(any(byte[].class), eq("commerce/product-privacy"), eq("image/png"), eq("png")))
                .thenReturn("https://oss.example.com/safe-worn.png");
        OpenCvCommerceProductImagePrivacyService service = new OpenCvCommerceProductImagePrivacyService(
                ossService,
                url -> createPngWithRedFacePatch(),
                image -> List.of()
        );

        CommerceProductCreateRequest.ProductImageItem item = productImage("https://oss.example.com/worn.png", "上身参考");

        List<CommerceProductCreateRequest.ProductImageItem> result = service.sanitizeProductReferences(List.of(item));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUsage()).isEqualTo("上身参考");
        assertThat(result.get(0).getUrl()).isEqualTo("https://oss.example.com/safe-worn.png");

        ArgumentCaptor<byte[]> imageCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ossService).uploadImage(imageCaptor.capture(), eq("commerce/product-privacy"), eq("image/png"), eq("png"));
        BufferedImage maskedImage = ImageIO.read(new java.io.ByteArrayInputStream(imageCaptor.getValue()));
        assertThat(maskedImage).isNotNull();
        assertThat(maskedImage.getRGB(40, 30)).isNotEqualTo(Color.RED.getRGB());
    }

    private CommerceProductCreateRequest.ProductImageItem productImage(String url, String usage) {
        CommerceProductCreateRequest.ProductImageItem item = new CommerceProductCreateRequest.ProductImageItem();
        item.setUrl(url);
        item.setUsage(usage);
        return item;
    }

    private byte[] createPngWithRedFacePatch() throws Exception {
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        for (int y = 20; y < 44; y++) {
            for (int x = 30; x < 54; x++) {
                image.setRGB(x, y, Color.RED.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
