package com.brainboosterlab.channel.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YouTubeUploadPackTest {
    @TempDir Path directory;

    @Test void defaultPromptIsSpecificAndRequiresTruthfulFamilyFriendlyPackaging() {
        var spec = PilotFixtures.sample();
        var prompt = new YouTubeMetadataAi("mock", "gpt-4o-mini").defaultPrompt(spec, null, "Puzzle Pop");
        assertThat(prompt).contains("Puzzle Pop", spec.title(), "PRIVATE ANSWER: OPTION", "exactly three A/B concepts",
            "Do not decide YouTube's 'made for kids' audience setting", "Do not invent social links");
    }

    @Test void defaultPromptStaysWithinLimitForTwentyLongPuzzleScenes() {
        var puzzles = java.util.stream.IntStream.range(0, 20).mapToObj(i -> new EpisodeSpec.Puzzle("visual",
            "Puzzle " + i, "Setup detail ".repeat(10), "Which scene shows the correct choice?", java.util.List.of("One short rule"),
            java.util.List.of(new EpisodeSpec.Choice("A", "choice A", "scene A"),
                new EpisodeSpec.Choice("B", "choice B", "scene B"), new EpisodeSpec.Choice("C", "choice C", "scene C")),
            "A", "Proof detail ".repeat(6), "Detailed scene description ".repeat(55), 10)).toList();
        var spec = new EpisodeSpec("A Long Family Puzzle Episode", puzzles);

        String prompt = new YouTubeMetadataAi("mock", "gpt-4o-mini").defaultPrompt(spec, null, "Puzzle Pop");

        assertThat(prompt.length()).isLessThan(YouTubeMetadataAi.MAX_PROMPT_CHARS);
        assertThat(prompt).contains("PUZZLE 20", "PRIVATE ANSWER: OPTION A");
    }

    @Test void uploadPromptSanitizerRemovesHiddenControlsAndFormatCharacters() {
        String prompt = YouTubeMetadataAi.sanitizePrompt("Keep this\u0000 line\tand this\u200B\r\nnext line");

        assertThat(prompt).isEqualTo("Keep this line and this\nnext line");
        assertThat(prompt.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint) && codePoint != '\n')).isTrue();
    }

    @Test void createsThreeRealThumbnailFilesAtYouTubeLandscapeSize() throws Exception {
        var spec = PilotFixtures.sample();
        var pack = new YouTubeMetadataAi("mock", "gpt-4o-mini").generate(spec, null, "Puzzle Pop", "gpt-4o-mini", "");
        pack.validate(spec);
        BufferedImage art = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < art.getHeight(); y++) for (int x = 0; x < art.getWidth(); x++)
            art.setRGB(x, y, new Color(30 + x % 180, 50 + y % 160, 150).getRGB());
        for (int i = 0; i < spec.puzzles().size(); i++) ImageIO.write(art, "png", directory.resolve("art-" + i + ".png").toFile());

        new YouTubeThumbnailRenderer().render(spec, directory, "Puzzle Pop", pack);

        for (int i = 1; i <= 3; i++) {
            Path file = directory.resolve("youtube-thumbnail-" + i + ".jpg");
            assertThat(Files.size(file)).isPositive();
            BufferedImage thumbnail = ImageIO.read(file.toFile());
            assertThat(thumbnail.getWidth()).isEqualTo(3840);
            assertThat(thumbnail.getHeight()).isEqualTo(2160);
            assertThat(Files.size(file)).isLessThan(2_000_000);
        }
    }

    @Test void validatesNoMoreThanThreeRelevantHashtagsAndThreeTitleThumbnailPairs() {
        var spec = PilotFixtures.sample();
        var pack = new YouTubeMetadataAi("mock", "gpt-4o-mini").generate(spec, null, "Puzzle Pop", "gpt-4o-mini", "");
        assertThatThrownBy(() -> new YouTubeUploadPack(pack.recommendedTitle(), pack.titleReason(), pack.description(),
            java.util.List.of("#one", "#two", "#three", "#four"), pack.tags(), pack.playlistSuggestion(),
            pack.categorySuggestion(), pack.videoLanguage(), pack.pinnedComment(), pack.variants()).validate(spec))
            .hasMessageContaining("one to three");
    }

    @Test void replacesMissingOrOverlongAdvisoryThumbnailDirectionsLocally() {
        var spec = PilotFixtures.sample();
        var pack = new YouTubeMetadataAi("mock", "gpt-4o-mini").generate(spec, null, "Puzzle Pop", "gpt-4o-mini", "");
        var malformedVariants = java.util.List.of(
            new YouTubeUploadPack.Variant(1, pack.variants().getFirst().title(), pack.variants().getFirst().thumbnailText(), "  \n\t  "),
            new YouTubeUploadPack.Variant(2, pack.variants().get(1).title(), pack.variants().get(1).thumbnailText(), "too long ".repeat(40)),
            pack.variants().get(2));
        var normalized = new YouTubeUploadPack(pack.recommendedTitle(), pack.titleReason(), pack.description(), pack.hashtags(),
            pack.tags(), pack.playlistSuggestion(), pack.categorySuggestion(), pack.videoLanguage(), pack.pinnedComment(),
            malformedVariants).withSafeThumbnailDirections();

        normalized.validate(spec);
        assertThat(normalized.variants().get(0).thumbnailDirection()).contains("puzzle 1");
        assertThat(normalized.variants().get(1).thumbnailDirection()).hasSizeLessThanOrEqualTo(240);
    }
}
