// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.util.Configuration;
import com.azure.core.util.Context;
import com.azure.core.util.HttpClientOptions;
import com.azure.core.util.UrlBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.DownloadRetryOptions;
import com.azure.storage.blob.options.BlobDownloadToFileOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.azure.storage.common.ParallelTransferOptions;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public class Icm387357955 {
    private static final String CONTAINER_NAME = "icm387357955";
    private static final String BLOB_NAME = UUID.randomUUID().toString();
    private static final String UPSTREAM_URI_HEADER = "X-Upstream-Base-Uri";
    private static final String HTTP_FAULT_INJECTOR_RESPONSE_HEADER = "x-ms-faultinjector-response-option";
    private static final String FAULT_TRACKING_CONTEXT_KEY = "fault-tracking";
    private static final Set<OpenOption> OPEN_OPTIONS_SET = new HashSet<>(Arrays.asList(
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
    private static final OpenOption[] OPEN_OPTIONS_ARRAY = new OpenOption[] {
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
    };

    public static void main(String[] args) throws IOException {
        String dataDirectory = Configuration.getGlobalConfiguration().get("DEBUG_SHARE");
        Path icmData = Paths.get(dataDirectory + "/icm-data");
        if (Files.exists(icmData)) {
            for (File file : icmData.toFile().listFiles()) {
                file.delete();
            }

            Files.delete(icmData);
        }

        Files.createDirectory(icmData);

        String downloadType = (args.length >= 1) ? args[0] : "file";

        int loops = 100;
        if (args.length >= 2) {
            loops = Math.max(loops, Integer.parseInt(args[1]));
        }

        String connectionString = Configuration.getGlobalConfiguration().get("STORAGE_CONNECTION_STRING");

        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();

        BlobClient setupClient = serviceClient.createBlobContainerIfNotExists(CONTAINER_NAME)
            .getBlobClient(BLOB_NAME);

        // Random data size not aligned to a specific power of 2.
        // This will be three chunks being downloaded using the default 4MB chunks.
        byte[] data = new byte[9 * 1024 * 1024 - 1];
        new Random().nextBytes(data);
        Path realData = getPath("real_data-" + UUID.randomUUID() + ".txt");
        System.out.println("--------------");
        System.out.println("Real data is in file: " + realData);
        System.out.println("--------------");
        Files.write(realData, data);
        setupClient.upload(new ByteArrayInputStream(data), true);

        HttpLogOptions logOptions = new HttpLogOptions()
                .setAllowedHeaderNames(Set.of("x-ms-range", "Content-Range", "Accept-Ranges"))
                .setLogLevel(HttpLogDetailLevel.BODY_AND_HEADERS);
        BlobClient downloadClient = new BlobClientBuilder()
            .connectionString(connectionString)
            .containerName(CONTAINER_NAME)
            .blobName(BLOB_NAME)
            .httpClient(new HttpFaultInjectingHttpClient(HttpClient.createDefault(), false))
            // Use a 10-second timeout for stalled reads as we'll have a lot of those running against HTTP fault injector.
            .clientOptions(new HttpClientOptions().setReadTimeout(Duration.ofSeconds(10)))
            .httpLogOptions(logOptions)
            .buildClient();

        Consumer<DownloadRequest> downloadFunction;
        if ("file".equalsIgnoreCase(downloadType)) {
            downloadFunction = Icm387357955::downloadToFile;
        } else if ("stream".equalsIgnoreCase(downloadType)) {
            downloadFunction = Icm387357955::downloadStream;
        } else if ("openRead".equalsIgnoreCase(downloadType)) {
            downloadFunction = Icm387357955::openReadInputStream;
        } else if ("eagerRead".equalsIgnoreCase(downloadType)) {
            downloadFunction = Icm387357955::eagerlyReadResponseWithChunks;
        } else if ("singleShot".equalsIgnoreCase(downloadType)) {
            downloadFunction = Icm387357955::singleShotDownload;
        } else {
            throw new RuntimeException("Invalid 'downloadType': " + downloadType);
        }

        // Change this to run parallel or run more counts or infinitely.
        IntStream.range(0, loops).parallel().forEach(iteration -> {
            Path downloadPath = getPath(UUID.randomUUID() + ".txt");

            try {
                Queue<String> faultTypes = new ConcurrentLinkedQueue<>();
                Context context = new Context(FAULT_TRACKING_CONTEXT_KEY, faultTypes);
                downloadFunction.accept(new DownloadRequest(downloadClient, downloadPath, context));

                byte[] downloadData = Files.readAllBytes(downloadPath);
                if (data.length != downloadData.length) {
                    System.out.println("Run " + iteration + " downloaded a different amount of data than was expected. "
                        + "Expected: " + data.length + ", received: " + downloadData.length + ". "
                        + "The following fault types were used: \n" + String.join("\n", faultTypes)
                        + ".\n Downloaded file is: " + downloadPath);
                }

                int mismatchIndex = Arrays.mismatch(data, downloadData);
                if (mismatchIndex != -1) {
                    System.out.println("Run " + iteration + " downloaded data mismatched with actual data on index: "
                        + mismatchIndex + ". The following fault types were used: \n"
                        + String.join("\n", faultTypes) + ".\n Downloaded file is: " + downloadPath);

                } else {
                    System.out.println("Run " + iteration + " properly downloaded all data.");
                    Files.deleteIfExists(downloadPath);
                }
            } catch (Exception ex) {
                System.err.println("Ran into an error while downloading iteration " + iteration + "."
                    + ex.getMessage());
            }
        });
    }

    // Download to file API customer is using.
    private static void downloadToFile(DownloadRequest downloadRequest) {
        downloadRequest.client.downloadToFileWithResponse(
            new BlobDownloadToFileOptions(downloadRequest.path.toString()).setOpenOptions(OPEN_OPTIONS_SET),
            null, downloadRequest.context);
    }

    // Another option to download to file using FileOutputStream.
    private static void downloadStream(DownloadRequest downloadRequest) {
        try (OutputStream outputStream = Files.newOutputStream(downloadRequest.path, OPEN_OPTIONS_ARRAY)) {
            downloadRequest.client.downloadStreamWithResponse(outputStream, null, null, null, false, null,
                downloadRequest.context);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // Another option to download using "open read" concept.
    private static void openReadInputStream(DownloadRequest downloadRequest) {
        try (InputStream openRead = downloadRequest.client.openInputStream(null, downloadRequest.context);
             OutputStream outputStream = Files.newOutputStream(downloadRequest.path, OPEN_OPTIONS_ARRAY)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = openRead.read(buffer)) != -1) {
                outputStream.write(buffer, 0, count);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // Another option to have the HttpClient eagerly read the HTTP response before returning the data with "smart"
    // retry disabled.
    private static void eagerlyReadResponseWithChunks(DownloadRequest downloadRequest) {
        downloadRequest.client.downloadToFileWithResponse(new BlobDownloadToFileOptions(
            downloadRequest.path.toString())
            .setDownloadRetryOptions(new DownloadRetryOptions().setMaxRetryRequests(0)) // Disable "smart" retry.
            .setOpenOptions(OPEN_OPTIONS_SET), null,
            downloadRequest.context.addData("azure-eagerly-read-response", true));
    }

    // Single shot download
    private static void singleShotDownload(DownloadRequest downloadRequest) {
        BlobDownloadToFileOptions options = new BlobDownloadToFileOptions(downloadRequest.path.toString())
            .setParallelTransferOptions(new ParallelTransferOptions()
                .setMaxConcurrency(1)
                .setMaxSingleUploadSizeLong(BlockBlobClient.MAX_UPLOAD_BLOB_BYTES_LONG)
                .setBlockSizeLong(BlockBlobClient.MAX_STAGE_BLOCK_BYTES_LONG))
            .setDownloadRetryOptions(new DownloadRetryOptions().setMaxRetryRequests(0))
            .setOpenOptions(OPEN_OPTIONS_SET);

        downloadRequest.client.downloadToFileWithResponse(options, null, downloadRequest.context);
    }

    private static final class HttpFaultInjectingHttpClient implements HttpClient {
        private final HttpClient wrappedHttpClient;
        private final boolean https;

        HttpFaultInjectingHttpClient(HttpClient wrappedHttpClient, boolean https) {
            this.wrappedHttpClient = wrappedHttpClient;
            this.https = https;
        }

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            return send(request, Context.NONE);
        }

        @SuppressWarnings("unchecked")
        @Override
        public Mono<HttpResponse> send(HttpRequest request, Context context) {
            URL originalUrl = request.getUrl();
            request.setHeader(UPSTREAM_URI_HEADER, originalUrl.toString()).setUrl(rewriteUrl(originalUrl));
            String faultType = faultInjectorHandling();
            ((Queue<String>) context.getData(FAULT_TRACKING_CONTEXT_KEY).get())
                .add(createMetadata(faultType, request.getHeaders()));
            request.setHeader(HTTP_FAULT_INJECTOR_RESPONSE_HEADER, faultType);

            return wrappedHttpClient.send(request, context)
                .map(response -> {
                    HttpRequest request1 = response.getRequest();
                    request1.getHeaders().remove(UPSTREAM_URI_HEADER);
                    request1.setUrl(originalUrl);

                    return response;
                });
        }

        @SuppressWarnings("unchecked")
        @Override
        public HttpResponse sendSync(HttpRequest request, Context context) {
            URL originalUrl = request.getUrl();
            request.setHeader(UPSTREAM_URI_HEADER, originalUrl.toString()).setUrl(rewriteUrl(originalUrl));
            String faultType = faultInjectorHandling();
            ((Queue<String>) context.getData(FAULT_TRACKING_CONTEXT_KEY).get())
                .add(createMetadata(faultType, request.getHeaders()));
            request.setHeader(HTTP_FAULT_INJECTOR_RESPONSE_HEADER, faultType);

            HttpResponse response = wrappedHttpClient.sendSync(request, context);
            response.getRequest().setUrl(originalUrl);
            response.getRequest().getHeaders().remove(UPSTREAM_URI_HEADER);

            return response;
        }

        private static String createMetadata(String faultType, HttpHeaders headers) {
            return "[faultType: " + faultType + ", range: " + headers.get("x-ms-range")
                + ", clientRequestId: " + headers.get(HttpHeaderName.X_MS_CLIENT_REQUEST_ID) + "]";
        }

        private URL rewriteUrl(URL originalUrl) {
            try {
                return UrlBuilder.parse(originalUrl)
                    .setScheme(https ? "https" : "http")
                    .setHost("localhost")
                    .setPort(https ? 7778 : 7777)
                    .toUrl();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        private static String faultInjectorHandling() {
            // f: Full response
            // p: Partial Response (full headers, 50% of body), then wait indefinitely
            // pc: Partial Response (full headers, 50% of body), then close (TCP FIN)
            // pa: Partial Response (full headers, 50% of body), then abort (TCP RST)
            // pn: Partial Response (full headers, 50% of body), then finish normally
            // n: No response, then wait indefinitely
            // nc: No response, then close (TCP FIN)
            // na: No response, then abort (TCP RST)
            double random = Math.random();

            if (random >= 0.25D) {
                // 75% of requests complete without error.
                return "f";
            } else {
                if (random <= 0.01D) {
                    // 1% of request completely fail.
                    if (random <= 0.003D) {
                        return "n";
                    } else if (random <= 0.007D) {
                        return "nc";
                    } else {
                        return "na";
                    }
                } else {
                    // 24% of requests partially complete.
                    if (random < 0.07D) {
                        return "p";
                    } else if (random < 0.13D) {
                        return "pc";
                    } else if (random < 0.19D) {
                        return "pa";
                    } else {
                        return "pn";
                    }
                }
            }
        }
    }

    private static final class DownloadRequest {
        private final BlobClient client;
        private final Path path;
        private final Context context;

        DownloadRequest(BlobClient client, Path path, Context context) {
            this.client = client;
            this.path = path;
            this.context = context;
        }
    }

    private static Path getPath(String fileName) {
        String dataDirectory = Configuration.getGlobalConfiguration().get("DEBUG_SHARE");
        return Paths.get(dataDirectory + "/icm-data").resolve(fileName);
    }
}
