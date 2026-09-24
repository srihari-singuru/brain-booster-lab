package com.brainboosterlab.channel.studio;

import java.util.List;

/** Copy-ready metadata and three title/thumbnail concepts for the YouTube upload screen. */
record YouTubeUploadPack(
    String recommendedTitle,
    String titleReason,
    String description,
    List<String> hashtags,
    List<String> tags,
    String playlistSuggestion,
    String categorySuggestion,
    String videoLanguage,
    String pinnedComment,
    List<Variant> variants
) {
    record Variant(int puzzleNumber, String title, String thumbnailText, String thumbnailDirection) {}

    /** The renderer composes thumbnails from artwork and headline copy; this direction is advisory only. */
    YouTubeUploadPack withSafeThumbnailDirections() {
        if (variants == null) return this;
        var safeVariants = variants.stream().map(variant -> {
            if (variant == null) return null;
            String direction = variant.thumbnailDirection() == null ? ""
                : variant.thumbnailDirection().replaceAll("[\\p{Cc}&&[^\\n\\t]]", "")
                    .replaceAll("\\s+", " ").trim();
            if (direction.isBlank() || direction.length() > 240) {
                direction = "Use puzzle " + variant.puzzleNumber()
                    + " artwork as the background; keep the main clue clear and put the short headline in open space.";
            }
            return new Variant(variant.puzzleNumber(), variant.title(), variant.thumbnailText(), direction);
        }).toList();
        return new YouTubeUploadPack(recommendedTitle, titleReason, description, hashtags, tags, playlistSuggestion,
            categorySuggestion, videoLanguage, pinnedComment, safeVariants);
    }

    void validate(EpisodeSpec spec) {
        require(recommendedTitle, 100, "Recommended title");
        require(titleReason, 300, "Title reason");
        requireMultiline(description, 5000, "Description");
        require(playlistSuggestion, 100, "Playlist suggestion");
        require(categorySuggestion, 40, "Category suggestion");
        require(videoLanguage, 40, "Video language");
        require(pinnedComment, 500, "Pinned comment");
        EpisodeSpec.require(categorySuggestion.equals("Entertainment") || categorySuggestion.equals("Education"),
            "Choose Entertainment or Education as the category suggestion");
        EpisodeSpec.require(hashtags != null && !hashtags.isEmpty() && hashtags.size() <= 3,
            "Create one to three relevant hashtags");
        EpisodeSpec.require(tags != null && tags.size() <= 20, "Create no more than 20 optional tags");
        EpisodeSpec.require(variants != null && variants.size() == 3, "Create exactly three title and thumbnail options");
        EpisodeSpec.require(variants != null && !variants.isEmpty() && variants.getFirst() != null
            && recommendedTitle.equals(variants.getFirst().title()), "The recommended title must match the first thumbnail option");
        for (String hashtag : hashtags) {
            require(hashtag, 40, "Hashtag");
            EpisodeSpec.require(hashtag.matches("#[\\p{L}\\p{N}_]+"), "Hashtags must start with # and contain no spaces");
        }
        for (String tag : tags) require(tag, 60, "Tag");
        for (int i = 0; i < variants.size(); i++) {
            Variant variant = variants.get(i);
            EpisodeSpec.require(variant != null && variant.puzzleNumber() >= 1 && variant.puzzleNumber() <= spec.puzzles().size(),
                "Each thumbnail option must feature an existing puzzle");
            require(variant.title(), 100, "Variant title");
            require(variant.thumbnailText(), 38, "Thumbnail text");
            require(variant.thumbnailDirection(), 240, "Thumbnail direction");
            EpisodeSpec.require(variant.thumbnailText().trim().split("\\s+").length <= 5,
                "Thumbnail copy must be five words or fewer");
        }
    }

    private static void require(String value, int max, String name) {
        EpisodeSpec.require(value != null && !value.isBlank() && value.length() <= max
            && value.codePoints().allMatch(c -> c >= 32 && c != 127), name + " is missing or too long");
    }

    private static void requireMultiline(String value, int max, String name) {
        EpisodeSpec.require(value != null && !value.isBlank() && value.length() <= max
            && value.codePoints().allMatch(c -> c == '\n' || c >= 32 && c != 127), name + " is missing or too long");
    }
}
