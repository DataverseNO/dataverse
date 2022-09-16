package edu.harvard.iq.dataverse.util;

/*
  Eko Indarto, DANS
  Vic Ding, DANS

  This file prepares the resources used in Signposting

  It requires correspondence configuration to function well.
  The configuration key used is SignpostingConf.

  useDefaultFileType is an on/off switch during linkset creating time, it controls whether the default type is
  used, which is always Dataset

  The configuration can be modified during run time by the administrator.
 */

import edu.harvard.iq.dataverse.*;
import edu.harvard.iq.dataverse.dataaccess.StorageIO;
import edu.harvard.iq.dataverse.dataaccess.SwiftAccessIO;
import edu.harvard.iq.dataverse.dataset.DatasetUtil;
import edu.harvard.iq.dataverse.license.License;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import static edu.harvard.iq.dataverse.util.json.NullSafeJsonBuilder.jsonObjectBuilder;

public class SignpostingResources {
    private static final Logger logger = Logger.getLogger(SignpostingResources.class.getCanonicalName());
    SystemConfig systemConfig;
    DatasetVersion workingDatasetVersion;
    static final String defaultFileTypeValue = "https://schema.org/Dataset";
    static final int defaultMaxLinks = 5;
    int maxAuthors;
    int maxItems;

    public SignpostingResources(SystemConfig systemConfig, DatasetVersion workingDatasetVersion, String authorLimitSetting, String itemLimitSetting) {
        this.systemConfig = systemConfig;
        this.workingDatasetVersion = workingDatasetVersion;
        maxAuthors = SystemConfig.getIntLimitFromStringOrDefault(itemLimitSetting, defaultMaxLinks);
        maxItems = SystemConfig.getIntLimitFromStringOrDefault(authorLimitSetting, defaultMaxLinks);
    }


    /**
     * Get key, values of signposting items and return as string
     *
     * @return comma delimited string
     */
    public String getLinks() {
        List<String> valueList = new LinkedList<>();
        Dataset ds = workingDatasetVersion.getDataset();

        String identifierSchema = getAuthorsAsString(getAuthorURLs(true));
        if (identifierSchema != null && !identifierSchema.isEmpty()) {
            valueList.add(identifierSchema);
        }

        if (!Objects.equals(ds.getPersistentURL(), "")) {
            String citeAs = "<" + ds.getPersistentURL() + ">;rel=\"cite-as\"";
            valueList.add(citeAs);
        }

        List<FileMetadata> fms = workingDatasetVersion.getFileMetadatas();
        String items = getItems(fms);
        if (items != null && !Objects.equals(items, "")) {
            valueList.add(items);
        }

        String describedby = "<" + ds.getGlobalId().toURL().toString() + ">;rel=\"describedby\"" + ";type=\"" + "application/vnd.citationstyles.csl+json\"";
        describedby += ",<" + systemConfig.getDataverseSiteUrl() + "/api/datasets/export?exporter=schema.org&persistentId="
                + ds.getProtocol() + ":" + ds.getAuthority() + "/" + ds.getIdentifier() + ">;rel=\"describedby\"" + ";type=\"application/json+ld\"";
        valueList.add(describedby);

        String type = "<https://schema.org/AboutPage>;rel=\"type\"";
        type = "<https://schema.org/AboutPage>;rel=\"type\",<" + defaultFileTypeValue + ">;rel=\"type\"";
        valueList.add(type);

        String licenseString = DatasetUtil.getLicenseURI(workingDatasetVersion) + ";rel=\"license\"";
        valueList.add(licenseString);

        String linkset = "<" + systemConfig.getDataverseSiteUrl() + "/api/datasets/:persistentId/versions/"
                + workingDatasetVersion.getVersionNumber() + "." + workingDatasetVersion.getMinorVersionNumber()
                + "/linkset?persistentId=" + ds.getProtocol() + ":" + ds.getAuthority() + "/" + ds.getIdentifier() + "> ; rel=\"linkset\";type=\"application/linkset+json\"";
        valueList.add(linkset);
        logger.info(String.format("valueList is: %s", valueList));

        return String.join(", ", valueList);
    }

    public JsonArrayBuilder getJsonLinkset() {
        Dataset ds = workingDatasetVersion.getDataset();
        GlobalId gid = new GlobalId(ds);
        String landingPage = systemConfig.getDataverseSiteUrl() + "/dataset.xhtml?persistentId=" + ds.getProtocol() + ":" + ds.getAuthority() + "/" + ds.getIdentifier();
        JsonArrayBuilder authors = getJsonAuthors(getAuthorURLs(false));
        JsonArrayBuilder items = getJsonItems();

        License license = workingDatasetVersion.getTermsOfUseAndAccess().getLicense();
        String licenseString = license.getUri().toString();

        JsonArrayBuilder mediaTypes = Json.createArrayBuilder();
        mediaTypes.add(
                jsonObjectBuilder().add(
                        "href",
                        gid.toURL().toString()
                ).add(
                        "type",
                        "application/vnd.citationstyles.csl+json"
                )
        );

        mediaTypes.add(
                jsonObjectBuilder().add(
                        "href",
                        systemConfig.getDataverseSiteUrl() + "/api/datasets/export?exporter=schema.org&persistentId=" + ds.getProtocol() + ":" + ds.getAuthority() + "/" + ds.getIdentifier()
                ).add(
                        "type",
                        "application/json+ld"
                )
        );
        JsonArrayBuilder linksetJsonObj = Json.createArrayBuilder();

        JsonObjectBuilder mandatory;
        mandatory = jsonObjectBuilder().add("anchor", landingPage)
                .add("cite-as", Json.createArrayBuilder().add(jsonObjectBuilder().add("href", ds.getPersistentURL())))
                .add("type",
                        Json.createArrayBuilder().add(jsonObjectBuilder().add("href", "https://schema.org/AboutPage"))
                                .add(jsonObjectBuilder().add("href", defaultFileTypeValue)));

        if (authors != null) {
            mandatory.add("author", authors);
        }
        if (licenseString != null && !licenseString.isBlank()) {
            mandatory.add("license", jsonObjectBuilder().add("href", licenseString));
        }
        if (!mediaTypes.toString().isBlank()) {
            mandatory.add("describedby", mediaTypes);
        }
        if (items != null) {
            mandatory.add("item", items);
        }
        linksetJsonObj.add(mandatory);

        // remove scholarly type as shown already on landing page
        for (FileMetadata fm : workingDatasetVersion.getFileMetadatas()) {
            DataFile df = fm.getDataFile();
            JsonObjectBuilder itemAnchor = jsonObjectBuilder().add("anchor", getPublicDownloadUrl(df));
            itemAnchor.add("collection", Json.createArrayBuilder().add(jsonObjectBuilder()
                    .add("href", landingPage)));
            linksetJsonObj.add(itemAnchor);
        }

        return linksetJsonObj;
    }

    /*Method retrieves all the authors of a DatasetVersion with a valid URL and puts them in a list
     * @param limit - if true, will return an empty list (for level 1) if more than maxAuthor authors with URLs are found 
     */
    private List<String> getAuthorURLs(boolean limit) {
        List<String> authorURLs = new ArrayList<String>(maxAuthors);
        int visibleAuthorCounter = 0;

        for (DatasetAuthor da : workingDatasetVersion.getDatasetAuthors()) {
            logger.fine(String.format("idtype: %s; idvalue: %s, affiliation: %s; identifierUrl: %s", da.getIdType(),
                    da.getIdValue(), da.getAffiliation(), da.getIdentifierAsUrl()));
            String authorURL = "";
            authorURL = getAuthorUrl(da);
            if (authorURL != null && !authorURL.isBlank()) {
                authorURLs.add(authorURL);
                visibleAuthorCounter++;
                // return empty if number of visible author more than max allowed
                if (visibleAuthorCounter >= maxAuthors) {
                    authorURLs.clear();
                    break;
                }

            }
        }
        return authorURLs;
    }


    /**
     * Get Authors as string
     * For example:
     * if author has VIAF
     * Link: <http://viaf.org/viaf/:id/>; rel="author"
     *
     * @param datasetAuthorURLs list of all DatasetAuthors with a valid URL
     * @return all the author links in a string
     */
    private String getAuthorsAsString(List<String> datasetAuthorURLs) {
        String singleAuthorString;
        String identifierSchema = null;
        for (String authorURL : datasetAuthorURLs) {
                singleAuthorString = "<" + authorURL + ">;rel=\"author\"";
                if (identifierSchema == null) {
                    identifierSchema = singleAuthorString;
                } else {
                    identifierSchema = String.join(",", identifierSchema, singleAuthorString);
                }
        }
        logger.fine(String.format("identifierSchema: %s", identifierSchema));
        return identifierSchema;
    }

    /* 
     * 
     */
    private String getAuthorUrl(DatasetAuthor da) {
        String authorURL = "";
        //If no type and there's a value, assume it is a URL (is this reasonable?)
        //Otherise, get the URL using the type and value
        if (da.getIdType() != null && !da.getIdType().isBlank() && da.getIdValue()!=null) {
            authorURL = da.getIdValue();
        } else {
            authorURL = da.getIdentifierAsUrl();
        }
        return authorURL;
    }

    private JsonArrayBuilder getJsonAuthors(List<String> datasetAuthorURLs) {
        if(datasetAuthorURLs.isEmpty()) {
            return null;
        }
        JsonArrayBuilder authors = Json.createArrayBuilder();
        for (String authorURL : datasetAuthorURLs) {
                authors.add(jsonObjectBuilder().add("href", authorURL));
        }
        return authors;
    }

    private String getItems(List<FileMetadata> fms) {
        if (fms.size() > maxItems) {
            logger.info(String.format("maxItem is %s and fms size is %s", maxItems, fms.size()));
            return null;
        }

        String itemString = null;
        for (FileMetadata fm : fms) {
            DataFile df = fm.getDataFile();
            if (itemString == null) {
                itemString = "<" + getPublicDownloadUrl(df) + ">;rel=\"item\";type=\"" + df.getContentType() + "\"";
            } else {
                itemString = String.join(",", itemString, "<" + getPublicDownloadUrl(df) + ">;rel=\"item\";type=\"" + df.getContentType() + "\"");
            }
        }
        return itemString;
    }

    private JsonArrayBuilder getJsonItems() {
        JsonArrayBuilder items = Json.createArrayBuilder();
        for (FileMetadata fm : workingDatasetVersion.getFileMetadatas()) {
            DataFile df = fm.getDataFile();
            items.add(jsonObjectBuilder().add("href", getPublicDownloadUrl(df)).add("type", df.getContentType()));
        }

        return items;
    }
    
    private String getPublicDownloadUrl(DataFile dataFile) {
        StorageIO<DataFile> storageIO = null;
        try {
            storageIO = dataFile.getStorageIO();
        } catch (IOException e) {
            logger.warning(String.format("Error getting storageID from file; original error message is: %s", e.getLocalizedMessage()));
        }

        if (storageIO instanceof SwiftAccessIO) {
            String fileDownloadUrl;
            SwiftAccessIO<DataFile> swiftIO = (SwiftAccessIO<DataFile>) storageIO;
            try {
                swiftIO.open();
            } catch (IOException e) {
                logger.warning(String.format("Error opening the swiftIO; original error message is: %s", e.getLocalizedMessage()));
            }

            //if its a public install, lets just give users the permanent URL!
            if (systemConfig.isPublicInstall()) {
                fileDownloadUrl = swiftIO.getRemoteUrl();
            } else {
                //TODO: if a user has access to this file, they should be given the swift url
                // perhaps even we could use this as the "private url"
                fileDownloadUrl = swiftIO.getTemporarySwiftUrl();
            }
            // close the stream
            swiftIO.closeInputStream();
            return fileDownloadUrl;

        }

        return FileUtil.getPublicDownloadUrl(systemConfig.getDataverseSiteUrl(), null, dataFile.getId());
    }
}
