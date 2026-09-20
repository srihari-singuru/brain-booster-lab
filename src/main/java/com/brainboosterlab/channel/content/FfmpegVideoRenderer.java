package com.brainboosterlab.channel.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class FfmpegVideoRenderer implements VideoRenderer {

    private final String ffmpegPath;
    private final Path outputDirectory;

    FfmpegVideoRenderer(
            @Value("${brain-booster.render.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${brain-booster.render.output-dir:outputs/rendered}") String outputDirectory
    ) {
        this.ffmpegPath = ffmpegPath;
        this.outputDirectory = Path.of(outputDirectory);
    }

    @Override
    public RenderResult render(ContentJob job) {
        try {
            Files.createDirectories(outputDirectory);
            Path artifact = outputDirectory.resolve(job.getId() + ".mp4").toAbsolutePath();
            List<String> command = List.of(
                    ffmpegPath,
                    "-y",
                    "-f", "lavfi",
                    "-i", "color=c=0x1f6feb:s=1920x1080:r=30",
                    "-t", "3",
                    "-metadata", "title=" + job.getTitle(),
                    "-pix_fmt", "yuv420p",
                    artifact.toString()
            );
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("FFmpeg exited with " + exitCode + ": " + output);
            }
            return new RenderResult(artifact.toString(), String.join(" ", command));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start FFmpeg", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg render was interrupted", exception);
        }
    }
}
