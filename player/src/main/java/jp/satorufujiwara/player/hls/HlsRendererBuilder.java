package jp.satorufujiwara.player.hls;

import com.google.android.exoplayer.DefaultLoadControl;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecSelector;
import com.google.android.exoplayer.MediaCodecUtil.DecoderQueryException;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.chunk.VideoFormatSelectorUtil;
import com.google.android.exoplayer.hls.DefaultHlsTrackSelector;
import com.google.android.exoplayer.hls.HlsChunkSource;
import com.google.android.exoplayer.hls.HlsMasterPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylistParser;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.hls.PtsTimestampAdjusterProvider;
import com.google.android.exoplayer.metadata.Id3Parser;
import com.google.android.exoplayer.metadata.MetadataTrackRenderer;
import com.google.android.exoplayer.text.TextTrackRenderer;
import com.google.android.exoplayer.text.eia608.Eia608TrackRenderer;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.ManifestFetcher;
import com.google.android.exoplayer.util.ManifestFetcher.ManifestCallback;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Handler;

import java.io.IOException;
import java.util.Map;

import jp.satorufujiwara.player.LimitedBandwidthMeter;
import jp.satorufujiwara.player.Player;
import jp.satorufujiwara.player.RendererBuilder;
import jp.satorufujiwara.player.RendererBuilderCallback;

/**
 * A {@link RendererBuilder} for HLS.
 */
public class HlsRendererBuilder extends RendererBuilder<HlsEventProxy> {

    long limitBitrate = Long.MAX_VALUE;
    LimitedBandwidthMeter bandwidthMeter;
    final HlsChunkSourceCreator hlsChunkSourceCreator;
    final int textBufferSegmentCount;
    private AsyncRendererBuilder currentAsyncBuilder;

    HlsRendererBuilder(Context context, Handler eventHandler, HlsEventProxy eventProxy,
            String userAgent, Uri uri, int bufferSegmentSize, int bufferSegmentCount,
            int textBufferSegmentCount, HlsChunkSourceCreator hlsChunkSourceCreator) {
        super(context, eventHandler, eventProxy, userAgent, uri, bufferSegmentSize,
                bufferSegmentCount);
        this.textBufferSegmentCount = textBufferSegmentCount;
        this.hlsChunkSourceCreator = hlsChunkSourceCreator;
    }

    @Override
    protected void buildRenderers(RendererBuilderCallback callback) {
        currentAsyncBuilder = new AsyncRendererBuilder(this, callback);
        currentAsyncBuilder.init();
    }

    @Override
    protected void cancel() {
        if (currentAsyncBuilder != null) {
            currentAsyncBuilder.cancel();
            currentAsyncBuilder = null;
        }
    }

    @Override
    protected void setLimitBitrate(long bitrate) {
        limitBitrate = bitrate;
        if (bandwidthMeter != null) {
            bandwidthMeter.setLimitBitrate(bitrate);
        }
    }

    private final class AsyncRendererBuilder implements ManifestCallback<HlsPlaylist> {

        private HlsRendererBuilder rendererBuilder;
        private final RendererBuilderCallback callback;
        private final ManifestFetcher<HlsPlaylist> playlistFetcher;

        private boolean canceled;

        public AsyncRendererBuilder(HlsRendererBuilder rendererBuilder,
                RendererBuilderCallback callback) {
            this.rendererBuilder = rendererBuilder;
            this.callback = callback;
            HlsPlaylistParser parser = new HlsPlaylistParser();
            playlistFetcher = new ManifestFetcher<>(rendererBuilder.uri.toString(),
                    new DefaultUriDataSource(rendererBuilder.context,
                            rendererBuilder.userAgent), parser);
        }

        public void init() {
            playlistFetcher.singleLoad(rendererBuilder.eventHandler.getLooper(), this);
        }

        public void cancel() {
            canceled = true;
            rendererBuilder = null;
        }

        @Override
        public void onSingleManifestError(IOException e) {
            if (canceled) {
                return;
            }
            callback.onRenderersError(e);
        }

        @Override
        public void onSingleManifest(HlsPlaylist manifest) {
            if (canceled) {
                return;
            }
            final Context context = rendererBuilder.context;
            final Handler handler = rendererBuilder.eventHandler;
            final LoadControl loadControl = new DefaultLoadControl(
                    new DefaultAllocator(rendererBuilder.bufferSegmentSize));
            final LimitedBandwidthMeter bandwidthMeter = new LimitedBandwidthMeter(handler,
                    eventProxy);
            bandwidthMeter.setLimitBitrate(limitBitrate);
            rendererBuilder.bandwidthMeter = bandwidthMeter;
            PtsTimestampAdjusterProvider timestampAdjusterProvider
                    = new PtsTimestampAdjusterProvider();

            int[] variantIndices = null;
            if (manifest instanceof HlsMasterPlaylist) {
                HlsMasterPlaylist masterPlaylist = (HlsMasterPlaylist) manifest;
                try {
                    variantIndices = VideoFormatSelectorUtil.selectVideoFormatsForDefaultDisplay(
                            context, masterPlaylist.variants, null, false);
                } catch (DecoderQueryException e) {
                    callback.onRenderersError(e);
                    return;
                }
            }
            final HlsEventProxy eventProxy = rendererBuilder.eventProxy;

            // Build the video/audio/metadata renderers.
            final DataSource dataSource = new DefaultUriDataSource(context, bandwidthMeter,
                    rendererBuilder.userAgent);
            final HlsChunkSource chunkSource;
            if (rendererBuilder.hlsChunkSourceCreator != null) {
                chunkSource = rendererBuilder.hlsChunkSourceCreator.create(dataSource,
                        rendererBuilder.uri.toString(), manifest, bandwidthMeter,
                        timestampAdjusterProvider, variantIndices);
            } else {
                chunkSource = new HlsChunkSource(true /* isMaster */, dataSource,
                        rendererBuilder.uri.toString(), manifest,
                        DefaultHlsTrackSelector.newDefaultInstance(context), bandwidthMeter,
                        timestampAdjusterProvider, HlsChunkSource.ADAPTIVE_MODE_SPLICE);

            }
            HlsSampleSource sampleSource = new HlsSampleSource(chunkSource, loadControl,
                    rendererBuilder.bufferSegmentSize * rendererBuilder
                            .bufferSegmentCount, handler, eventProxy, Player.TYPE_VIDEO);
            MediaCodecVideoTrackRenderer videoRenderer = new MediaCodecVideoTrackRenderer(context,
                    sampleSource, MediaCodecSelector.DEFAULT,
                    MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 5000, handler, eventProxy, 50);
            MediaCodecAudioTrackRenderer audioRenderer = new MediaCodecAudioTrackRenderer(
                    sampleSource, MediaCodecSelector.DEFAULT, null, true, handler, eventProxy,
                    AudioCapabilities.getCapabilities(context), AudioManager.STREAM_MUSIC);
            MetadataTrackRenderer<Map<String, Object>> id3Renderer = new MetadataTrackRenderer<>(
                    sampleSource, new Id3Parser(), eventProxy, handler.getLooper());

            // Build the text renderer, preferring Webvtt where available.
            boolean preferWebvtt = false;
            if (manifest instanceof HlsMasterPlaylist) {
                preferWebvtt = !((HlsMasterPlaylist) manifest).subtitles.isEmpty();
            }
            TrackRenderer textRenderer;
            if (preferWebvtt) {
                DataSource textDataSource = new DefaultUriDataSource(context, bandwidthMeter,
                        userAgent);
                HlsChunkSource textChunkSource = new HlsChunkSource(false /* isMaster */,
                        textDataSource, rendererBuilder.uri.toString(), manifest,
                        DefaultHlsTrackSelector.newVttInstance(), bandwidthMeter,
                        timestampAdjusterProvider, HlsChunkSource.ADAPTIVE_MODE_SPLICE);
                HlsSampleSource textSampleSource = new HlsSampleSource(textChunkSource, loadControl,
                        rendererBuilder.textBufferSegmentCount * bufferSegmentSize, handler,
                        eventProxy,
                        Player.TYPE_TEXT);
                textRenderer = new TextTrackRenderer(textSampleSource, eventProxy,
                        handler.getLooper());
            } else {
                textRenderer = new Eia608TrackRenderer(sampleSource, eventProxy,
                        handler.getLooper());
            }

            TrackRenderer[] renderers = new TrackRenderer[Player.RENDERER_COUNT];
            renderers[Player.TYPE_VIDEO] = videoRenderer;
            renderers[Player.TYPE_AUDIO] = audioRenderer;
            renderers[Player.TYPE_METADATA] = id3Renderer;
            renderers[Player.TYPE_TEXT] = textRenderer;
            callback.onRenderers(renderers, bandwidthMeter);
        }

    }

}