package com.sismics.docs.rest.resource;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.jpa.*;
import com.sismics.docs.core.dao.jpa.criteria.DocumentCriteria;
import com.sismics.docs.core.dao.jpa.criteria.TagCriteria;
import com.sismics.docs.core.dao.jpa.dto.*;
import com.sismics.docs.core.event.DocumentCreatedAsyncEvent;
import com.sismics.docs.core.event.DocumentDeletedAsyncEvent;
import com.sismics.docs.core.event.DocumentUpdatedAsyncEvent;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.PdfUtil;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.AclUtil;
import com.sismics.rest.util.JsonUtil;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.util.mime.MimeType;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.DateTimeParser;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.*;

/**
 * Document REST resources.
 * 
 * @author bgamard
 */
@Path("/document")
public class DocumentResource extends BaseResource {
    /**
     * Returns a document.
     * 
     * @param documentId Document ID
     * @param shareId Share ID
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}")
    public Response get(
            @PathParam("id") String documentId,
            @QueryParam("share") String shareId) {
        authenticate();
        
        DocumentDao documentDao = new DocumentDao();
        DocumentDto documentDto = documentDao.getDocument(documentId, PermType.READ, getTargetIdList(shareId));
        if (documentDto == null) {
            throw new NotFoundException();
        }
            
        JsonObjectBuilder document = Json.createObjectBuilder()
                .add("id", documentDto.getId())
                .add("title", documentDto.getTitle())
                .add("description", JsonUtil.nullable(documentDto.getDescription()))
                .add("create_date", documentDto.getCreateTimestamp())
                .add("language", documentDto.getLanguage())
                .add("shared", documentDto.getShared())
                .add("file_count", documentDto.getFileCount());

        List<TagDto> tagDtoList = null;
        if (principal.isAnonymous()) {
            // No tags in anonymous mode (sharing)
            document.add("tags", Json.createArrayBuilder());
        } else {
            // Add tags visible by the current user on this document
            TagDao tagDao = new TagDao();
            tagDtoList = tagDao.findByCriteria(
                    new TagCriteria()
                            .setTargetIdList(getTargetIdList(null)) // No tags for shares
                            .setDocumentId(documentId),
                    new SortCriteria(1, true));
            JsonArrayBuilder tags = Json.createArrayBuilder();
            for (TagDto tagDto : tagDtoList) {
                tags.add(Json.createObjectBuilder()
                        .add("id", tagDto.getId())
                        .add("name", tagDto.getName())
                        .add("color", tagDto.getColor()));
            }
            document.add("tags", tags);
        }
        
        // Below is specific to GET /document/id
        document.add("subject", JsonUtil.nullable(documentDto.getSubject()));
        document.add("identifier", JsonUtil.nullable(documentDto.getIdentifier()));
        document.add("publisher", JsonUtil.nullable(documentDto.getPublisher()));
        document.add("format", JsonUtil.nullable(documentDto.getFormat()));
        document.add("source", JsonUtil.nullable(documentDto.getSource()));
        document.add("type", JsonUtil.nullable(documentDto.getType()));
        document.add("coverage", JsonUtil.nullable(documentDto.getCoverage()));
        document.add("rights", JsonUtil.nullable(documentDto.getRights()));
        document.add("creator", documentDto.getCreator());

        // Add ACL
        AclUtil.addAcls(document, documentId, getTargetIdList(shareId));

        // Add computed ACL
        if (tagDtoList != null) {
            JsonArrayBuilder aclList = Json.createArrayBuilder();
            for (TagDto tagDto : tagDtoList) {
                AclDao aclDao = new AclDao();
                List<AclDto> aclDtoList = aclDao.getBySourceId(tagDto.getId());
                for (AclDto aclDto : aclDtoList) {
                    aclList.add(Json.createObjectBuilder()
                            .add("perm", aclDto.getPerm().name())
                            .add("source_id", tagDto.getId())
                            .add("source_name", tagDto.getName())
                            .add("id", aclDto.getTargetId())
                            .add("name", JsonUtil.nullable(aclDto.getTargetName()))
                            .add("type", aclDto.getTargetType()));
                }
            }
            document.add("inherited_acls", aclList);
        }
        
        // Add contributors
        ContributorDao contributorDao = new ContributorDao();
        List<ContributorDto> contributorDtoList = contributorDao.getByDocumentId(documentId);
        JsonArrayBuilder contributorList = Json.createArrayBuilder();
        for (ContributorDto contributorDto : contributorDtoList) {
            contributorList.add(Json.createObjectBuilder()
                    .add("username", contributorDto.getUsername())
                    .add("email", contributorDto.getEmail()));
        }
        document.add("contributors", contributorList);
        
        // Add relations
        RelationDao relationDao = new RelationDao();
        List<RelationDto> relationDtoList = relationDao.getByDocumentId(documentId);
        JsonArrayBuilder relationList = Json.createArrayBuilder();
        for (RelationDto relationDto : relationDtoList) {
            relationList.add(Json.createObjectBuilder()
                    .add("id", relationDto.getId())
                    .add("title", relationDto.getTitle())
                    .add("source", relationDto.isSource()));
        }
        document.add("relations", relationList);
        
        return Response.ok().entity(document.build()).build();
    }
    
    /**
     * Export a document to PDF.
     * 
     * @param documentId Document ID
     * @param shareId Share ID
     * @param metadata Export metadata
     * @param comments Export comments
     * @param fitImageToPage Fit images to page
     * @param marginStr Margins
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}/pdf")
    public Response getPdf(
            @PathParam("id") String documentId,
            @QueryParam("share") String shareId,
            final @QueryParam("metadata") Boolean metadata,
            final @QueryParam("comments") Boolean comments,
            final @QueryParam("fitimagetopage") Boolean fitImageToPage,
            @QueryParam("margin") String marginStr) {
        authenticate();
        
        // Validate input
        final int margin = ValidationUtil.validateInteger(marginStr, "margin");
        
        // Get document and check read permission
        DocumentDao documentDao = new DocumentDao();
        final DocumentDto documentDto = documentDao.getDocument(documentId, PermType.READ, getTargetIdList(shareId));
        if (documentDto == null) {
            throw new NotFoundException();
        }
        
        // Get files
        FileDao fileDao = new FileDao();
        UserDao userDao = new UserDao();
        final List<File> fileList = fileDao.getByDocumentId(null, documentId);
        for (File file : fileList) {
            // A file is always encrypted by the creator of it
            // Store its private key to decrypt it
            User user = userDao.getById(file.getUserId());
            file.setPrivateKey(user.getPrivateKey());
        }
        
        // Convert to PDF
        StreamingOutput stream = new StreamingOutput() {
            @Override
            public void write(OutputStream outputStream) throws IOException, WebApplicationException {
                try (InputStream inputStream = PdfUtil.convertToPdf(documentDto, fileList, fitImageToPage, metadata, margin)) {
                    ByteStreams.copy(inputStream, outputStream);
                } catch (Exception e) {
                    throw new IOException(e);
                } finally {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
        };

        return Response.ok(stream)
                .header("Content-Type", MimeType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"" + documentDto.getTitle() + ".pdf\"")
                .build();
    }
    
    /**
     * Returns all documents.
     * 
     * @param limit Page limit
     * @param offset Page offset
     * @param sortColumn Sort column
     * @param asc Sorting
     * @param search Search query
     * @return Response
     */
    @GET
    @Path("list")
    public Response list(
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset,
            @QueryParam("sort_column") Integer sortColumn,
            @QueryParam("asc") Boolean asc,
            @QueryParam("search") String search) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        JsonObjectBuilder response = Json.createObjectBuilder();
        JsonArrayBuilder documents = Json.createArrayBuilder();
        
        DocumentDao documentDao = new DocumentDao();
        TagDao tagDao = new TagDao();
        PaginatedList<DocumentDto> paginatedList = PaginatedLists.create(limit, offset);
        SortCriteria sortCriteria = new SortCriteria(sortColumn, asc);
        DocumentCriteria documentCriteria = parseSearchQuery(search);
        documentCriteria.setTargetIdList(getTargetIdList(null));
        try {
            documentDao.findByCriteria(paginatedList, documentCriteria, sortCriteria);
        } catch (Exception e) {
            throw new ServerException("SearchError", "Error searching in documents", e);
        }

        for (DocumentDto documentDto : paginatedList.getResultList()) {
            // Get tags added by the current user on this document
            List<TagDto> tagDtoList = tagDao.findByCriteria(new TagCriteria()
                    .setTargetIdList(getTargetIdList(null))
                    .setDocumentId(documentDto.getId()), new SortCriteria(1, true));
            JsonArrayBuilder tags = Json.createArrayBuilder();
            for (TagDto tagDto : tagDtoList) {
                tags.add(Json.createObjectBuilder()
                        .add("id", tagDto.getId())
                        .add("name", tagDto.getName())
                        .add("color", tagDto.getColor()));
            }
            
            documents.add(Json.createObjectBuilder()
                    .add("id", documentDto.getId())
                    .add("title", documentDto.getTitle())
                    .add("description", JsonUtil.nullable(documentDto.getDescription()))
                    .add("create_date", documentDto.getCreateTimestamp())
                    .add("language", documentDto.getLanguage())
                    .add("shared", documentDto.getShared())
                    .add("file_count", documentDto.getFileCount())
                    .add("tags", tags));
        }
        response.add("total", paginatedList.getResultCount())
                .add("documents", documents);
        
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Parse a query according to the specified syntax, eg.:
     * tag:assurance tag:other before:2012 after:2011-09 shared:yes lang:fra thing
     * 
     * @param search Search query
     * @return DocumentCriteria
     */
    private DocumentCriteria parseSearchQuery(String search) {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        if (Strings.isNullOrEmpty(search)) {
            return documentCriteria;
        }
        
        TagDao tagDao = new TagDao();
        UserDao userDao = new UserDao();
        DateTimeParser[] parsers = { 
                DateTimeFormat.forPattern("yyyy").getParser(),
                DateTimeFormat.forPattern("yyyy-MM").getParser(),
                DateTimeFormat.forPattern("yyyy-MM-dd").getParser() };
        DateTimeFormatter yearFormatter = new DateTimeFormatter(null, parsers[0]);
        DateTimeFormatter monthFormatter = new DateTimeFormatter(null, parsers[1]);
        DateTimeFormatter dayFormatter = new DateTimeFormatter(null, parsers[2]);
        DateTimeFormatter formatter = new DateTimeFormatterBuilder().append( null, parsers ).toFormatter();
        
        String[] criteriaList = search.split("  *");
        List<String> query = new ArrayList<>();
        List<String> fullQuery = new ArrayList<>();
        for (String criteria : criteriaList) {
            String[] params = criteria.split(":");
            if (params.length != 2 || Strings.isNullOrEmpty(params[0]) || Strings.isNullOrEmpty(params[1])) {
                // This is not a special criteria
                query.add(criteria);
                continue;
            }

            switch (params[0]) {
                case "tag":
                    // New tag criteria
                    List<TagDto> tagDtoList = tagDao.findByCriteria(new TagCriteria().setTargetIdList(getTargetIdList(null)).setNameLike(params[1]), null);
                    if (documentCriteria.getTagIdList() == null) {
                        documentCriteria.setTagIdList(new ArrayList<String>());
                    }
                    if (tagDtoList.size() == 0) {
                        // No tag found, the request must returns nothing
                        documentCriteria.getTagIdList().add(UUID.randomUUID().toString());
                    }
                    for (TagDto tagDto : tagDtoList) {
                        documentCriteria.getTagIdList().add(tagDto.getId());
                    }
                    break;
                case "after":
                case "before":
                    // New date span criteria
                    try {
                        DateTime date = formatter.parseDateTime(params[1]);
                        if (params[0].equals("before")) documentCriteria.setCreateDateMax(date.toDate());
                        else documentCriteria.setCreateDateMin(date.toDate());
                    } catch (IllegalArgumentException e) {
                        // Invalid date, returns no documents
                        if (params[0].equals("before")) documentCriteria.setCreateDateMax(new Date(0));
                        else documentCriteria.setCreateDateMin(new Date(Long.MAX_VALUE / 2));
                    }
                    break;
                case "at":
                    // New specific date criteria
                    try {
                        if (params[1].length() == 10) {
                            DateTime date = dayFormatter.parseDateTime(params[1]);
                            documentCriteria.setCreateDateMin(date.toDate());
                            documentCriteria.setCreateDateMax(date.plusDays(1).minusSeconds(1).toDate());
                        } else if (params[1].length() == 7) {
                            DateTime date = monthFormatter.parseDateTime(params[1]);
                            documentCriteria.setCreateDateMin(date.toDate());
                            documentCriteria.setCreateDateMax(date.plusMonths(1).minusSeconds(1).toDate());
                        } else if (params[1].length() == 4) {
                            DateTime date = yearFormatter.parseDateTime(params[1]);
                            documentCriteria.setCreateDateMin(date.toDate());
                            documentCriteria.setCreateDateMax(date.plusYears(1).minusSeconds(1).toDate());
                        }
                    } catch (IllegalArgumentException e) {
                        // Invalid date, returns no documents
                        documentCriteria.setCreateDateMin(new Date(0));
                        documentCriteria.setCreateDateMax(new Date(0));
                    }
                    break;
                case "shared":
                    // New shared state criteria
                    if (params[1].equals("yes")) {
                        documentCriteria.setShared(true);
                    }
                    break;
                case "lang":
                    // New language criteria
                    if (Constants.SUPPORTED_LANGUAGES.contains(params[1])) {
                        documentCriteria.setLanguage(params[1]);
                    }
                    break;
                case "by":
                    // New creator criteria
                    User user = userDao.getActiveByUsername(params[1]);
                    if (user == null) {
                        // This user doesn't exists, return nothing
                        documentCriteria.setCreatorId(UUID.randomUUID().toString());
                    } else {
                        // This user exists, search its documents
                        documentCriteria.setCreatorId(user.getId());
                    }
                    break;
                case "full":
                    // New full content search criteria
                    fullQuery.add(params[1]);
                    break;
                default:
                    query.add(criteria);
                    break;
            }
        }
        
        documentCriteria.setSearch(Joiner.on(" ").join(query));
        documentCriteria.setFullSearch(Joiner.on(" ").join(fullQuery));
        return documentCriteria;
    }

    /**
     * Creates a new document.
     * 
     * @param title Title
     * @param description Description
     * @param subject Subject
     * @param identifier Identifier
     * @param publisher Publisher
     * @param format Format
     * @param source Source
     * @param type Type
     * @param coverage Coverage
     * @param rights Rights
     * @param tagList Tags
     * @param relationList Relations
     * @param language Language
     * @param createDateStr Creation date
     * @return Response
     */
    @PUT
    public Response add(
            @FormParam("title") String title,
            @FormParam("description") String description,
            @FormParam("subject") String subject,
            @FormParam("identifier") String identifier,
            @FormParam("publisher") String publisher,
            @FormParam("format") String format,
            @FormParam("source") String source,
            @FormParam("type") String type,
            @FormParam("coverage") String coverage,
            @FormParam("rights") String rights,
            @FormParam("tags") List<String> tagList,
            @FormParam("relations") List<String> relationList,
            @FormParam("language") String language,
            @FormParam("create_date") String createDateStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input data
        title = ValidationUtil.validateLength(title, "title", 1, 100, false);
        language = ValidationUtil.validateLength(language, "language", 3, 3, false);
        description = ValidationUtil.validateLength(description, "description", 0, 4000, true);
        subject = ValidationUtil.validateLength(subject, "subject", 0, 500, true);
        identifier = ValidationUtil.validateLength(identifier, "identifier", 0, 500, true);
        publisher = ValidationUtil.validateLength(publisher, "publisher", 0, 500, true);
        format = ValidationUtil.validateLength(format, "format", 0, 500, true);
        source = ValidationUtil.validateLength(source, "source", 0, 500, true);
        type = ValidationUtil.validateLength(type, "type", 0, 100, true);
        coverage = ValidationUtil.validateLength(coverage, "coverage", 0, 100, true);
        rights = ValidationUtil.validateLength(rights, "rights", 0, 100, true);
        Date createDate = ValidationUtil.validateDate(createDateStr, "create_date", true);
        if (!Constants.SUPPORTED_LANGUAGES.contains(language)) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} is not a supported language", language));
        }
        
        // Create the document
        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(principal.getId());
        document.setTitle(title);
        document.setDescription(description);
        document.setSubject(subject);
        document.setIdentifier(identifier);
        document.setPublisher(publisher);
        document.setFormat(format);
        document.setSource(source);
        document.setType(type);
        document.setCoverage(coverage);
        document.setRights(rights);
        document.setLanguage(language);
        if (createDate == null) {
            document.setCreateDate(new Date());
        } else {
            document.setCreateDate(createDate);
        }
        String documentId = documentDao.create(document, principal.getId());
        
        // Create read ACL
        AclDao aclDao = new AclDao();
        Acl acl = new Acl();
        acl.setPerm(PermType.READ);
        acl.setSourceId(documentId);
        acl.setTargetId(principal.getId());
        aclDao.create(acl, principal.getId());
        
        // Create write ACL
        acl = new Acl();
        acl.setPerm(PermType.WRITE);
        acl.setSourceId(documentId);
        acl.setTargetId(principal.getId());
        aclDao.create(acl, principal.getId());
        
        // Update tags
        updateTagList(documentId, tagList);
        
        // Update relations
        updateRelationList(documentId, relationList);
        
        // Raise a document created event
        DocumentCreatedAsyncEvent documentCreatedAsyncEvent = new DocumentCreatedAsyncEvent();
        documentCreatedAsyncEvent.setUserId(principal.getId());
        documentCreatedAsyncEvent.setDocument(document);
        AppContext.getInstance().getAsyncEventBus().post(documentCreatedAsyncEvent);
        
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("id", documentId);
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Updates the document.
     * 
     * @param title Title
     * @param description Description
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}")
    public Response update(
            @PathParam("id") String id,
            @FormParam("title") String title,
            @FormParam("description") String description,
            @FormParam("subject") String subject,
            @FormParam("identifier") String identifier,
            @FormParam("publisher") String publisher,
            @FormParam("format") String format,
            @FormParam("source") String source,
            @FormParam("type") String type,
            @FormParam("coverage") String coverage,
            @FormParam("rights") String rights,
            @FormParam("tags") List<String> tagList,
            @FormParam("relations") List<String> relationList,
            @FormParam("language") String language,
            @FormParam("create_date") String createDateStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input data
        title = ValidationUtil.validateLength(title, "title", 1, 100, false);
        language = ValidationUtil.validateLength(language, "language", 3, 3, false);
        description = ValidationUtil.validateLength(description, "description", 0, 4000, true);
        subject = ValidationUtil.validateLength(subject, "subject", 0, 500, true);
        identifier = ValidationUtil.validateLength(identifier, "identifier", 0, 500, true);
        publisher = ValidationUtil.validateLength(publisher, "publisher", 0, 500, true);
        format = ValidationUtil.validateLength(format, "format", 0, 500, true);
        source = ValidationUtil.validateLength(source, "source", 0, 500, true);
        type = ValidationUtil.validateLength(type, "type", 0, 100, true);
        coverage = ValidationUtil.validateLength(coverage, "coverage", 0, 100, true);
        rights = ValidationUtil.validateLength(rights, "rights", 0, 100, true);
        Date createDate = ValidationUtil.validateDate(createDateStr, "create_date", true);
        if (language != null && !Constants.SUPPORTED_LANGUAGES.contains(language)) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} is not a supported language", language));
        }
        
        // Check write permission
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(id, PermType.WRITE, getTargetIdList(null))) {
            throw new ForbiddenClientException();
        }
        
        // Get the document
        DocumentDao documentDao = new DocumentDao();
        Document document = documentDao.getById(id);
        if (document == null) {
            throw new NotFoundException();
        }
        
        // Update the document
        document.setTitle(title);
        document.setDescription(description);
        document.setSubject(subject);
        document.setIdentifier(identifier);
        document.setPublisher(publisher);
        document.setFormat(format);
        document.setSource(source);
        document.setType(type);
        document.setCoverage(coverage);
        document.setRights(rights);
        document.setLanguage(language);
        if (createDate == null) {
            document.setCreateDate(new Date());
        } else {
            document.setCreateDate(createDate);
        }
        
        documentDao.update(document, principal.getId());
        
        // Update tags
        updateTagList(id, tagList);
        
        // Update relations
        updateRelationList(id, relationList);
        
        // Raise a document updated event (with the document to update Lucene)
        DocumentUpdatedAsyncEvent documentUpdatedAsyncEvent = new DocumentUpdatedAsyncEvent();
        documentUpdatedAsyncEvent.setUserId(principal.getId());
        documentUpdatedAsyncEvent.setDocumentId(id);
        AppContext.getInstance().getAsyncEventBus().post(documentUpdatedAsyncEvent);
        
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("id", id);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Update tags list on a document.
     * 
     * @param documentId Document ID
     * @param tagList Tag ID list
     */
    private void updateTagList(String documentId, List<String> tagList) {
        if (tagList != null) {
            TagDao tagDao = new TagDao();
            Set<String> tagSet = new HashSet<>();
            Set<String> tagIdSet = new HashSet<>();
            List<TagDto> tagDtoList = tagDao.findByCriteria(new TagCriteria().setTargetIdList(getTargetIdList(null)), null);
            for (TagDto tagDto : tagDtoList) {
                tagIdSet.add(tagDto.getId());
            }
            for (String tagId : tagList) {
                if (!tagIdSet.contains(tagId)) {
                    throw new ClientException("TagNotFound", MessageFormat.format("Tag not found: {0}", tagId));
                }
                tagSet.add(tagId);
            }
            tagDao.updateTagList(documentId, tagSet);
        }
    }
    
    /**
     * Update relations list on a document.
     * 
     * @param documentId Document ID
     * @param relationList Relation ID list
     */
    private void updateRelationList(String documentId, List<String> relationList) {
        if (relationList != null) {
            DocumentDao documentDao = new DocumentDao();
            RelationDao relationDao = new RelationDao();
            Set<String> documentIdSet = new HashSet<>();
            for (String targetDocId : relationList) {
                // ACL are not checked, because the editing user is not forced to view the target document
                Document document = documentDao.getById(targetDocId);
                if (document != null && !documentId.equals(targetDocId)) {
                    documentIdSet.add(targetDocId);
                }
            }
            relationDao.updateRelationList(documentId, documentIdSet);
        }
    }
    
    /**
     * Deletes a document.
     * 
     * @param id Document ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    public Response delete(
            @PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the document
        DocumentDao documentDao = new DocumentDao();
        FileDao fileDao = new FileDao();
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(id, PermType.WRITE, getTargetIdList(null))) {
            throw new NotFoundException();
        }
        List<File> fileList = fileDao.getByDocumentId(principal.getId(), id);
        
        // Delete the document
        documentDao.delete(id, principal.getId());
        
        // Raise file deleted events (don't bother sending document updated event)
        for (File file : fileList) {
            FileDeletedAsyncEvent fileDeletedAsyncEvent = new FileDeletedAsyncEvent();
            fileDeletedAsyncEvent.setUserId(principal.getId());
            fileDeletedAsyncEvent.setFile(file);
            AppContext.getInstance().getAsyncEventBus().post(fileDeletedAsyncEvent);
        }
        
        // Raise a document deleted event
        DocumentDeletedAsyncEvent documentDeletedAsyncEvent = new DocumentDeletedAsyncEvent();
        documentDeletedAsyncEvent.setUserId(principal.getId());
        documentDeletedAsyncEvent.setDocumentId(id);
        AppContext.getInstance().getAsyncEventBus().post(documentDeletedAsyncEvent);
        
        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
}
