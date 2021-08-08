package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.youtube.YoutubeService;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.subscription.SubscriptionItem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.annotation.Nonnull;

import static org.schabi.newpipe.extractor.subscription.SubscriptionExtractor.ContentSource.INPUT_STREAM;

/**
 * Extract subscriptions from a Google takeout export (the user has to get the JSON out of the zip)
 */
public class YoutubeSubscriptionExtractor extends SubscriptionExtractor {
    private static final String BASE_CHANNEL_URL = "https://www.youtube.com/channel/";

    public YoutubeSubscriptionExtractor(final YoutubeService youtubeService) {
        super(youtubeService, Collections.singletonList(INPUT_STREAM));
    }

    @Override
    public String getRelatedUrl() {
        return "https://takeout.google.com/takeout/custom/youtube";
    }

    @Override
        public List<SubscriptionItem> fromInputStream(@Nonnull final InputStream contentInputStream)
            throws ExtractionException {
        return fromJsonInputStream(contentInputStream);
    }

    @Override
    public List<SubscriptionItem> fromInputStream(@Nonnull final InputStream contentInputStream, String contentType)
            throws ExtractionException {
        switch (contentType) {
            case "json":
            case "application/json":
                return fromJsonInputStream(contentInputStream);
            case "csv":
            case "text/csv":
            case "text/comma-separated-values":
                return fromCsvInputStream(contentInputStream);
            case "zip":
            case "application/zip":
                return fromZipInputStream(contentInputStream);
            default:
                throw new InvalidSourceException("Unsupported content type: " + contentType);
        }
    }

    public List<SubscriptionItem> fromJsonInputStream(@Nonnull final InputStream contentInputStream)
            throws ExtractionException {
        final JsonArray subscriptions;
        try {
            subscriptions = JsonParser.array().from(contentInputStream);
        } catch (JsonParserException e) {
            throw new InvalidSourceException("Invalid json input stream", e);
        }

        boolean foundInvalidSubscription = false;
        final List<SubscriptionItem> subscriptionItems = new ArrayList<>();
        for (final Object subscriptionObject : subscriptions) {
            if (!(subscriptionObject instanceof JsonObject)) {
                foundInvalidSubscription = true;
                continue;
            }

            final JsonObject subscription = ((JsonObject) subscriptionObject).getObject("snippet");
            final String id = subscription.getObject("resourceId").getString("channelId", "");
            if (id.length() != 24) { // e.g. UCsXVk37bltHxD1rDPwtNM8Q
                foundInvalidSubscription = true;
                continue;
            }

            subscriptionItems.add(new SubscriptionItem(service.getServiceId(),
                    BASE_CHANNEL_URL + id, subscription.getString("title", "")));
        }

        if (foundInvalidSubscription && subscriptionItems.isEmpty()) {
            throw new InvalidSourceException("Found only invalid channel ids");
        }
        return subscriptionItems;
    }

    public List<SubscriptionItem> fromZipInputStream(@Nonnull final InputStream contentInputStream)
            throws ExtractionException {
        ZipInputStream zipInputStream = new ZipInputStream(contentInputStream);

        try {
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                if (IsEntryPathMatch(zipEntry.getName())) {
                    try {
                        return fromCsvInputStream(zipInputStream);
                    }
                    catch (ExtractionException e) {
                        throw new InvalidSourceException("Error reading contents of file '" + zipEntry.getName() + "'");
                    }
                }
            }
        } catch (IOException e) {
            throw new InvalidSourceException("Error reading contents of zip file", e);
        }

        throw new InvalidSourceException("Unable to find a subscriptions.csv file (try extracting and selecting the csv file)");
    }

    public static final String[] ENTRY_PATHS = {
            "Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv",
            "Takeout/YouTube y YouTube Music/suscripciones/suscripciones.csv"
    };

    public static boolean IsEntryPathMatch(@Nonnull String entryName) {
        for (String entryPath : ENTRY_PATHS) {
            if (entryPath.equalsIgnoreCase(entryName)) {
                return true;
            }
        }
        return false;
    }

    public List<SubscriptionItem> fromCsvInputStream(@Nonnull final InputStream contentInputStream)
            throws ExtractionException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(contentInputStream))) {
            List<SubscriptionItem> subscriptionItems = new ArrayList<>();

            // Skip header
            br.readLine();

            String line;
            while ((line = br.readLine()) != null) {
                int i1 = line.indexOf(",");
                if (i1 == -1) continue;

                int i2 = line.indexOf(",", i1 + 1);
                if (i2 == -1) continue;

                String channelUrl = line.substring(i1 + 1, i2);
                String channelTitle = line.substring(i2 + 1);
                SubscriptionItem newItem = new SubscriptionItem(service.getServiceId(), channelUrl, channelTitle);
                subscriptionItems.add(newItem);
            }

            return subscriptionItems;
        } catch (IOException e) {
            throw new InvalidSourceException("Error reading CSV file");
        }
    }
}
