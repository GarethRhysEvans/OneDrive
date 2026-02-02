package org.devit;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;


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

import com.microsoft.aad.msal4j.*;
import com.microsoft.graph.models.Drive;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Allows user to maintain OneDrive files
 */
public class OneDrive {

    private static final Logger logger = LoggerFactory.getLogger(OneDrive.class);

    // Configuration from your Azure App Registration
    private static final String CLIENT_ID = System.getenv("AZURE_CLIENT_ID");
    private static final String TENANT_ID = System.getenv("AZURE_TENANT_ID");
    private static final String PFX_BASE64 = System.getenv("AZURE_CERT_PFX_BASE64");
    private static final String PFX_PASSWORD = System.getenv("AZURE_CERT_PASSWORD");

    // Define the scopes needed for OneDrive access
    private static final String[] SCOPES = {"https://graph.microsoft.com/.default"};

    // Defines the DriveId User
    private static final String USER_ID = System.getenv("USER_ID");

    private GraphServiceClient graphClient = null;
    private String driveId = null;
    private String accessToken = null;

    /**
     * Obtain the access token, graph client anf drive id.
     * Constructor
     */
    public OneDrive() throws Exception{

        // Get the token credential
        TokenCredential credential = getTokenCredential();

        this.graphClient = new GraphServiceClient(credential, SCOPES);

        this.driveId = getDriveId();

        logger.info("DriveId: {}", this.driveId );

    }


    /**
     * Create an empty file
     * @param folderPath    The Onedrive folder path
     * @param fileName      The filename to create
     * @return DriveItem    The empty file
     * @throws IOException  Failed to create empty file
     */
    public DriveItem createEmptyFile(String folderPath, String fileName) throws IOException {

        DriveItem folderItem = findFolder(folderPath);

        String urlString = "https://graph.microsoft.com/v1.0/drives/" + driveId + "/items/" + folderItem.getId() + "/children";

        URL url = new URL(urlString);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
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
     * Upload the file to the OneDrive folder path - will overwrite existing file
     * @param fileName      The file to upload (should include path)
     * @param folderPath    The folder path on OneDrive
     * @throws IOException  Failed to find file
     */
    public void upload(String fileName, String folderPath) throws IOException {

        // Check whether the source file exists
        File file = new File(fileName);

        if(!file.isFile() || !file.canRead()) {
            throw new RuntimeException("Failed to find file " + fileName);
        }

        // Find the file on OneDrive in the folder path specified.
        // If no file is found, create a blank file.
        DriveItem fileItem = null;
        try {
            fileItem = findFileByPathAndName(folderPath, file.getName());
        } catch (RuntimeException re) {
            fileItem = createEmptyFile(folderPath, file.getName());
        }

        // Update the file contents
        if(fileItem !=null && fileItem.getId() !=null) {

            try (FileInputStream fileStream = new FileInputStream(file)) {
                DriveItem uploadedItem = graphClient
                        .drives()
                        .byDriveId(Objects.requireNonNull(Objects.requireNonNull(fileItem.getParentReference()).getDriveId()))
                        .items()
                        .byDriveItemId(fileItem.getId())
                        .content()
                        .put(fileStream);

                if(uploadedItem !=null) {
                    logger.info("Uploaded file Id: {}", uploadedItem.getId());
                }
            }

        }

    }

    /**
     * Download the OneDrive file from the folder and store it in the localPath
     * @param folderPath    The OneDrive folder path
     * @param fileName      The OneDrive file
     * @param localPath     The local path to store the OneDrive file
     * @throws IOException  The file cannot be found
     */
    public void download( String folderPath, String fileName, String localPath) throws IOException{

        DriveItem fileItem = findFileByPathAndName(folderPath, fileName);

        InputStream in = graphClient
            .drives()
            .byDriveId(Objects.requireNonNull(Objects.requireNonNull(fileItem.getParentReference()).getDriveId()))
            .items()
            .byDriveItemId(Objects.requireNonNull(fileItem.getId()))
            .content()
            .get();

        if(in !=null) {
            FileOutputStream out = new FileOutputStream(localPath + File.separator + fileName);

            in.transferTo(out);
         }

    }

    /**
     * Find the OneDrive folder
     * @param folderPath    The folder to find
     * @return A pointe to the folder
     */
    private DriveItem findFolder(String folderPath) {
        return findFileByPathAndName(folderPath, null);
    }

    /**
     * Find a OneDrive File or Folder
     * @param folderPath    The OneDrive folder to find
     * @param fileName      The OneDrive file to find - can be null
     * @return  A pointer to the file or folder
     */
    private DriveItem findFileByPathAndName(String folderPath, String fileName) {

        // Set the current Folder to the root directory
        DriveItem currentFolder = graphClient
                .drives()
                .byDriveId(driveId)
                .root()
                .get();

        // If no folder is found, error.
        if(currentFolder == null || currentFolder.getId() == null) {
            throw new RuntimeException("Root folder not found: " + fileName);
        }

        // Traverse the folder path specified
        for (String folderName : folderPath.split("/")) {
            if (folderName.isEmpty()) continue; // skip leading slash

            // List the children of the currentFolder
            List<DriveItem> children = Optional.ofNullable(
                            graphClient
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
        if(fileName == null)
            return currentFolder;

        // Get a list of the files in the current folder
        List<DriveItem> finalChildren = Optional.ofNullable(
            graphClient
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
     * Find the drive id for the user specified
     * @return  the drive id
     */
    private String getDriveId() {
        // Get the folder for the named user
        Drive drive = graphClient
                .users()
                .byUserId(USER_ID)
                .drive()
                .get();

        return drive != null ? drive.getId() : null;
    }

    /**
     * Get the token for Sharepoint using the pfx file
     * @return
     * @throws IOException
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws KeyStoreException
     * @throws CertificateException
     * @throws NoSuchAlgorithmException
     * @throws UnrecoverableKeyException
     */
    private TokenCredential getTokenCredential() throws IOException, ExecutionException, InterruptedException, KeyStoreException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException {


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

        accessToken = result.accessToken();

        logger.info("Access Token acquired.");

        // Create a TokenCredential that always returns your token
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

    public class DriveWalker {

        public DriveWalker() {
        }

        public void list(String folder) {
            list(folder, null);
        }

        public void list(String folder, String url) {

            DriveItemCollectionResponse children = (url == null) ?
                    graphClient
                        .drives()
                        .byDriveId(driveId)
                        .items()
                        .byDriveItemId(folder)
                        .children()
                        .get() :

                    graphClient
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
                    logger.info("Folder: {} {}", child.getName(), child.getId());

                    list(child.getId());

                } else if (child.getFile() != null) {
                    logger.info("File: {} {}", child.getName(), child.getId());
                }

            }

            // Handle paging
            String nextLink = children.getOdataNextLink();

            if (nextLink != null && !nextLink.isEmpty()) {

                list("ignored", children.getOdataNextLink());
            }

        }
    }






}