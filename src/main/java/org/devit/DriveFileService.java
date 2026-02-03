package org.devit;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.microsoft.aad.msal4j.*;
import com.microsoft.graph.models.Drive;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.models.Site;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Provides basic OneDrive and SharePoint functions
 */
public class DriveFileService {

    private static final Logger logger = LoggerFactory.getLogger(DriveFileService.class);

    // Configuration from your Azure App Registration
    private static final String CLIENT_ID = System.getenv("AZURE_CLIENT_ID");
    private static final String TENANT_ID = System.getenv("AZURE_TENANT_ID");
    private static final String PFX_BASE64 = System.getenv("AZURE_CERT_PFX_BASE64");
    private static final String PFX_PASSWORD = System.getenv("AZURE_CERT_PASSWORD");

    // Define the scopes needed for OneDrive access
    private static final String[] SCOPES = {"https://graph.microsoft.com/.default"};

    private Builder data = null;

    private DriveFileService(Builder builder) {
        this.data = builder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private GraphServiceClient graphClient = null;
        private String accessToken = null;
        private String hostName = null;
        private String sitePath = null;
        private String userId = System.getenv("USER_ID");
        ;

        private String folderPath = null;
        private String fileName = null;

        private String localPath = null;
        private String localFile = null;

        private boolean isOneDrive = true;


        public DriveFileService build()
                throws UnrecoverableKeyException, CertificateException, IOException, KeyStoreException, NoSuchAlgorithmException, ExecutionException, InterruptedException {

            this.accessToken = getAccessToken();

            this.graphClient = new GraphServiceClient(getTokenCredential(this.accessToken), SCOPES);

            logger.info("Graph Client acquired.");

            return new DriveFileService(this);
        }


        public Builder setHostName(String hostName) {
            this.hostName = hostName;

            this.isOneDrive = (hostName == null);
            return this;
        }

        public Builder setSitePath(String sitePath) {
            this.sitePath = sitePath;
            return this;
        }

        public Builder setUserId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder setFolderPath(String folderPath) {
            this.folderPath = folderPath;
            return this;
        }

        public Builder setFileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder setLocalFile(String localFile) {
            this.localFile = localFile;
            return this;
        }
        public Builder setLocalPath(String localPath) {
            this.localPath = localPath;
            return this;
        }
    }


    /**
     * Get the access token for OpenDrive/Sharepoint using the pfx file
     *
     * @return AccessToken
     * @throws KeyStoreException
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws UnrecoverableKeyException
     * @throws CertificateException
     * @throws ExecutionException
     * @throws InterruptedException
     */
    private static String getAccessToken() throws KeyStoreException, IOException, NoSuchAlgorithmException, UnrecoverableKeyException, CertificateException, ExecutionException, InterruptedException {

        // Load PFX file
        byte[] pfxBytes = Base64.getDecoder().decode(PFX_BASE64);
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        keystore.load(new ByteArrayInputStream(pfxBytes), PFX_PASSWORD.toCharArray());

        String alias = keystore.aliases().nextElement();
        PrivateKey privateKey = (PrivateKey) keystore.getKey(alias, PFX_PASSWORD.toCharArray());
        X509Certificate certificate = (X509Certificate) keystore.getCertificate(alias);

        // Create client credential
        IClientCredential clientCredential =
                ClientCredentialFactory.createFromCertificate(privateKey, certificate);

        // Create MSAL4J confidential client
        ConfidentialClientApplication app =
                ConfidentialClientApplication.builder(CLIENT_ID, clientCredential)
                        .authority("https://login.microsoftonline.com/" + TENANT_ID)
                        .build();


        // Client credentials request
        ClientCredentialParameters parameters = ClientCredentialParameters.builder(Set.of(SCOPES))
                .build();

        // Acquire token
        CompletableFuture<IAuthenticationResult> future = app.acquireToken(parameters);
        IAuthenticationResult result = future.get();

        logger.info("Access Token acquired.");

        return result.accessToken();

    }

    /**
     * Get the Token Provider for use with the GraphService
     *
     * @param accessToken
     * @return TokenCredential
     */
    private static TokenCredential getTokenCredential(String accessToken) {

        TokenCredential credential = new TokenCredential() {
            @Override
            public Mono<AccessToken> getToken(TokenRequestContext request) {
                AccessToken token = new AccessToken(accessToken, OffsetDateTime.now().plusHours(1));
                return Mono.just(token);
            }
        };
        logger.info("Token Credential acquired.");

        return credential;


    }


    /**
     * Find the Site Id for the host and path
     *
     * @return the site Id
     */
    private String getSiteId() {
        String siteIdentifier = String.format("%s:/%s", data.hostName, data.sitePath);

        Site site = data.graphClient.sites().bySiteId(siteIdentifier).get();

        return site != null ? site.getId() : null;
    }


    /**
     * Find the drive id for the user specified
     *
     * @return the drive id
     */
    private String getDriveId() {
        // Get the folder for the named user
        Drive drive = (data.isOneDrive) ?
                data.graphClient
                        .users()
                        .byUserId(data.userId)
                        .drive()
                        .get() :

                data.graphClient
                        .sites()
                        .bySiteId(Objects.requireNonNull(getSiteId()))
                        .drive()
                        .get();

        return drive != null ? drive.getId() : null;
    }


    /**
     * Find the OneDrive/Sharepoint folder
     *
     * @param folderPath The folder to find
     * @return A pointe to the folder
     */
    private DriveItem findFolder(String folderPath) {
        return findFileByPathAndName(folderPath, null);
    }

    /**
     * Find a OneDrive/Sharepoint File or Folder
     *
     * @param folderPath The OneDrive folder to find
     * @param fileName   The OneDrive file to find - can be null
     * @return A pointer to the file or folder
     */
    private DriveItem findFileByPathAndName(String folderPath, String fileName) {

        String driveId = getDriveId();

        Objects.requireNonNull(driveId);

        DriveItem currentFolder =
                data.graphClient
                        .drives()
                        .byDriveId(driveId)
                        .root()
                        .get();

        // If no folder is found, error.
        if (currentFolder == null || currentFolder.getId() == null) {
            throw new RuntimeException("Root folder not found: " + fileName);
        }

        // Traverse the folder path specified
        for (String folderName : folderPath.split("/")) {
            if (folderName.isEmpty()) continue; // skip leading slash

            // List the children of the currentFolder
            List<DriveItem> children = Optional.ofNullable(
                            data.graphClient
                                    .drives()
                                    .byDriveId(driveId)
                                    .items()
                                    .byDriveItemId(Objects.requireNonNull(currentFolder.getId()))
                                    .children()
                                    .get()
                    ).map(DriveItemCollectionResponse::getValue)
                    .orElse(List.of());

            // Match the folder to the subfolders
            Optional<DriveItem> nextFolder = children.stream()
                    .filter(c -> c.getFolder() != null && Objects.equals(c.getName(), folderName))
                    .findFirst();

            // If the folder is found, set the current folder to the matching folder.
            // Otherwise, abort
            if (nextFolder.isPresent()) {
                currentFolder = nextFolder.get();

            } else {
                throw new RuntimeException("Folder not found: " + folderName);
            }
        }

        // Return the folder if no filename is set.
        if (fileName == null)
            return currentFolder;

        // Get a list of the files in the current folder
        List<DriveItem> finalChildren = Optional.ofNullable(
                        data.graphClient
                                .drives()
                                .byDriveId(driveId)
                                .items()
                                .byDriveItemId(Objects.requireNonNull(currentFolder.getId()))
                                .children()
                                .get()
                )
                .map(DriveItemCollectionResponse::getValue)
                .orElse(List.of());

        // Return a matching file or error
        return finalChildren.stream()
                .filter(f -> f.getFile() != null && Objects.equals(f.getName(), fileName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("File not found: " + fileName));

    }

    /**
     * Create an empty file
     *
     * @return DriveItem    The empty file
     * @throws IOException Failed to create empty file
     */
    private DriveItem createEmptyFile(String folderPath, String fileName) throws IOException {

        String driveId = getDriveId();

        Objects.requireNonNull(driveId);

        DriveItem folderItem = findFolder(folderPath);

        String urlString = "https://graph.microsoft.com/v1.0/drives/" + driveId + "/items/" + folderItem.getId() + "/children";

        URL url = new URL(urlString);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + data.accessToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String jsonBody = "{ \"name\": \"" + fileName + "\", \"file\": {} }";
        conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));

        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

        if (code >= 400) {
            throw new RuntimeException("Failed to create empty file: HTTP " + code + "\n" + response);
        }

        return findFileByPathAndName(folderPath, fileName);

    }

    /**
     * Delete the file from the OneDrive/Sharepoint folder
     * @throws IOException
     */
    public void delete() throws IOException {
        // Find the file on OneDrive in the folder path specified.

        DriveItem fileItem = findFileByPathAndName(data.folderPath, data.fileName);;
        Objects.requireNonNull(fileItem);

        String driveId = getDriveId();
        Objects.requireNonNull(driveId);


        String urlString = "https://graph.microsoft.com/v1.0/drives/" + driveId + "/items/" + fileItem.getId();

        URL url = new URL(urlString);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("DELETE");
        conn.setRequestProperty("Authorization", "Bearer " + data.accessToken);

        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

        if (code >= 400) {
            throw new RuntimeException("Failed to create empty file: HTTP " + code + "\n" + response);
        }

    }

    /**
     * Create an empty file
     * @throws IOException  The file cannot be created
     */
    public void createEmptyFile() throws IOException {
        createEmptyFile(data.folderPath, data.fileName);
    }

    /**
     * Downloads the OneDrive/SharePoint file from the specified folder and saves it to the local path.
     *
     * @throws IOException The file cannot be found
     */
    public void download() throws IOException {

        DriveItem fileItem = findFileByPathAndName(data.folderPath, data.fileName);

        InputStream in = data.graphClient
                .drives()
                .byDriveId(Objects.requireNonNull(Objects.requireNonNull(fileItem.getParentReference()).getDriveId()))
                .items()
                .byDriveItemId(Objects.requireNonNull(fileItem.getId()))
                .content()
                .get();

        if (in != null) {
            FileOutputStream out = new FileOutputStream(data.localPath + File.separator + data.fileName);

            in.transferTo(out);
        }

    }

    /**
     * Uploads the file to the specified OneDrive/SharePoint folder path.
     * Existing files are overwritten; missing files are created before uploading the new contents.
     *
     * @throws IOException Failed to find file
     */
    public void upload() throws IOException {

        // Check whether the source file exists
        File file = new File(data.localFile);

        if (!file.isFile() || !file.canRead()) {
            throw new RuntimeException("Failed to find file " + data.localFile);
        }

        // Find the file on OneDrive in the folder path specified.
        // If no file is found, create a blank file.
        DriveItem fileItem = null;
        try {
            fileItem = findFileByPathAndName(data.folderPath, file.getName());
        } catch (RuntimeException re) {
            fileItem = createEmptyFile(data.folderPath, file.getName());
        }

        // Update the file contents
        if (fileItem != null && fileItem.getId() != null) {

            try (FileInputStream fileStream = new FileInputStream(file)) {
                DriveItem uploadedItem = data.graphClient
                        .drives()
                        .byDriveId(Objects.requireNonNull(Objects.requireNonNull(fileItem.getParentReference()).getDriveId()))
                        .items()
                        .byDriveItemId(fileItem.getId())
                        .content()
                        .put(fileStream);

                if (uploadedItem != null) {
                    logger.info("Uploaded file Id: {}", uploadedItem.getId());
                }
            }

        }


    }

    /**
     * Lists all files recursively from the root directory onward.
     * @param isDebug  Print out display
     * @return  The List of the files
     * @throws IOException
     */
    public ArrayList<FileEntry> list(boolean isDebug) throws IOException {
        DriveWalker driveWalker = new DriveWalker();
        driveWalker.list("root");

        if(isDebug) {
            for (FileEntry fe : driveWalker.getDirectory()) {
                System.out.println(fe);

            }
        }

        return driveWalker.getDirectory();
    }


    public class DriveWalker {
        String driveId = null;

        ArrayList<FileEntry> directory = new ArrayList<FileEntry>();

        public DriveWalker() {
            this.driveId = getDriveId();

            Objects.requireNonNull(driveId);
        }

        public void list(String folder) {
            list(folder, null, 0);
        }

        public void list(String folder, String url, int depth) {

            DriveItemCollectionResponse children = (url == null) ?
                    data.graphClient
                            .drives()
                            .byDriveId(driveId)
                            .items()
                            .byDriveItemId(folder)
                            .children()
                            .get() :

                    data.graphClient
                            .drives()
                            .byDriveId(driveId)
                            .items()
                            .byDriveItemId(folder)
                            .children()
                            .withUrl(url)
                            .get();


            if (children == null || children.getValue() == null) return;

            for (DriveItem child : children.getValue()) {

                if (child.getFolder() != null) {

                    directory.add(new FileEntry(child, depth));

                    list(child.getId(), null, depth + 1);

                } else if (child.getFile() != null) {

                    directory.add(new FileEntry(child, depth));
                }

            }

            // Handle paging
            String nextLink = children.getOdataNextLink();

            if (nextLink != null && !nextLink.isEmpty()) {

                list("ignored", children.getOdataNextLink(), depth);
            }

        }

        public ArrayList<FileEntry> getDirectory() {
            return directory;
        }

    }

    public class FileEntry {
        DriveItem path;
        int depth;

        FileEntry(DriveItem path, int depth) {
            this.path = path;
            this.depth = depth;
        }

        public String toString() {
            return "  ".repeat(depth) + (path.getFolder() !=null ? "[D] " : "[F] ") + path.getName();
        }
    }
}