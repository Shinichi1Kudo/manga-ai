package com.manga.ai.common.constants;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreditConstantsTest {

    @Test
    void calculatesSeedanceVipCreditsByResolution() {
        assertThat(CreditConstants.getCreditsPerSecond("1080p", "seedance-2.0")).isEqualTo(67);
        assertThat(CreditConstants.getCreditsPerSecond("720p", "seedance-2.0")).isEqualTo(27);
        assertThat(CreditConstants.getCreditsPerSecond("480p", "seedance-2.0")).isEqualTo(16);
        assertThat(CreditConstants.getCreditsPerSecond("720p", "doubao-seedance-2-0-260128")).isEqualTo(27);
    }

    @Test
    void calculatesSeedanceFastVipCreditsByResolution() {
        assertThat(CreditConstants.getCreditsPerSecond("720p", "seedance-2.0-fast")).isEqualTo(22);
        assertThat(CreditConstants.getCreditsPerSecond("480p", "seedance-2.0-fast")).isEqualTo(11);
        assertThat(CreditConstants.getCreditsPerSecond("720p", "doubao-seedance-2-0-fast-260128")).isEqualTo(22);
        assertThat(CreditConstants.getCreditsPerSecond("720p", null)).isEqualTo(22);
    }

    @Test
    void calculatesKlingV3OmniCreditsByResolution() {
        assertThat(CreditConstants.getCreditsPerSecond("720p", "kling-v3-omni")).isEqualTo(15);
        assertThat(CreditConstants.getCreditsPerSecond("1080p", "kling-v3-omni")).isEqualTo(16);
    }

    @Test
    void multipliesRateByDuration() {
        assertThat(CreditConstants.calculateCredits("1080p", 8, "seedance-2.0")).isEqualTo(536);
        assertThat(CreditConstants.calculateCredits("720p", 8, "seedance-2.0-fast")).isEqualTo(176);
        assertThat(CreditConstants.calculateCredits("1080p", 8, "kling-v3-omni")).isEqualTo(128);
    }

    @Test
    void chargesGptImage2PerGeneratedImage() {
        assertThat(CreditConstants.CREDITS_PER_GPT_IMAGE2_IMAGE).isEqualTo(6);
    }
}
