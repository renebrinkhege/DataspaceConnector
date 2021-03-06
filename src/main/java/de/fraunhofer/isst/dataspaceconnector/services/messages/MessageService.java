package de.fraunhofer.isst.dataspaceconnector.services.messages;

import de.fraunhofer.iais.eis.*;
import de.fraunhofer.isst.dataspaceconnector.exceptions.ConnectorConfigurationException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.message.MessageBuilderException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.message.MessageException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.message.MessageNotSentException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.message.MessageResponseException;
import de.fraunhofer.isst.dataspaceconnector.services.resources.OfferedResourceServiceImpl;
import de.fraunhofer.isst.dataspaceconnector.services.resources.ResourceService;
import de.fraunhofer.isst.dataspaceconnector.services.utils.IdsUtils;
import de.fraunhofer.isst.dataspaceconnector.services.utils.UUIDUtils;
import de.fraunhofer.isst.ids.framework.communication.http.IDSHttpService;
import de.fraunhofer.isst.ids.framework.communication.http.InfomodelMessageBuilder;
import de.fraunhofer.isst.ids.framework.configuration.SerializerProvider;
import de.fraunhofer.isst.ids.framework.daps.ClaimsException;
import okhttp3.MultipartBody;
import org.apache.commons.fileupload.FileUploadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * Abstract class for building and sending IDS messages.
 */
@Service
public abstract class MessageService {

    private final Logger LOGGER = LoggerFactory.getLogger(MessageService.class);

    private final IDSHttpService idsHttpService;
    private final ResourceService resourceService;
    private final SerializerProvider serializerProvider;
    private final IdsUtils idsUtils;

    /**
     * Constructor for MessageService.
     *
     * @throws IllegalArgumentException if any of the parameters is null.
     */
    @Autowired
    public MessageService(IDSHttpService idsHttpService, IdsUtils idsUtils,
        SerializerProvider serializerProvider, OfferedResourceServiceImpl resourceService)
        throws IllegalArgumentException {
        if (idsHttpService == null)
            throw new IllegalArgumentException("The IDSHttpService cannot be null.");

        if (resourceService == null)
            throw new IllegalArgumentException("The OfferedResourceServiceImpl cannot be null.");

        if (serializerProvider == null)
            throw new IllegalArgumentException("The SerializerProvider cannot be null.");

        if (idsUtils == null)
            throw new IllegalArgumentException("The IdsUtils cannot be null.");

        this.idsHttpService = idsHttpService;
        this.resourceService = resourceService;
        this.serializerProvider = serializerProvider;
        this.idsUtils = idsUtils;
    }

    /**
     * Build an IDS message as request header.
     *
     * @return the message.
     * @throws MessageException if the message could not be created.
     */
    public abstract Message buildRequestHeader() throws MessageException;

    /**
     * Build an IDS message as response header.
     *
     * @return the message.
     * @throws MessageException if the message could not be created.
     */
    public abstract Message buildResponseHeader() throws MessageException;

    /**
     * Returns the recipient.
     * @return the recipient.
     */
    public abstract URI getRecipient();

    /**
     * Returns the serializer provider.
     * @return the serializer provider.
     */
    public SerializerProvider getSerializerProvider() {
        return serializerProvider;
    }

    /**
     * Sends an IDS message with header and payload using the IDS Framework.
     *
     * @param payload the message payload.
     * @return the HTTP response.
     * @throws MessageException if a header could not be built or the message could not be sent.
     */
    public Map<String, String> sendMessage(String payload) throws MessageException {
        Message message;
        try {
            message = buildRequestHeader();
        } catch (MessageBuilderException exception) {
            LOGGER.warn("Message could not be built. [exception=({})]", exception.getMessage());
            throw new MessageBuilderException("Message could not be built.", exception);
        }

        try {
            MultipartBody body = InfomodelMessageBuilder.messageWithString(message, payload);
            return idsHttpService.sendAndCheckDat(body, getRecipient());
        } catch (ClaimsException exception) {
            LOGGER.warn("Invalid DAT in incoming message. [exception=({})]", exception.getMessage());
            throw new MessageResponseException("Unexpected message answer.", exception);
        } catch (MessageNotSentException | FileUploadException | IOException exception) {
            LOGGER.warn("Message could not be sent. [exception=({})]", exception.getMessage());
            throw new MessageBuilderException("Message could not be sent.", exception);
        }
    }

    /**
     * Checks if the outbound model version of the requesting connector is listed in the inbound model versions.
     *
     * @param versionString the outbound model version of the requesting connector.
     * @return true, if the outbound model version of the requsting connector is supported; false otherwise
     * @throws ConnectorConfigurationException if no connector configuration was found
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean versionSupported(String versionString) throws ConnectorConfigurationException {
        final var connector = idsUtils.getConnector();

        for (var version : connector.getInboundModelVersion()) {
            if (version.equals(versionString)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds resource by a given artifact ID.
     *
     * @param artifactId ID of the artifact
     * @return the resource
     */
    public Resource findResourceFromArtifactId(UUID artifactId) {
        for (var resource : resourceService.getResources()) {
            for (var representation : resource.getRepresentation()) {
                final var representationId = UUIDUtils.uuidFromUri(representation.getId());

                if (representationId.equals(artifactId)) {
                    return resource;
                }
            }
        }
        return null;
    }

    /**
     * Extracts the artifact ID from contract request.
     *
     * @param request the contract
     * @return The artifact ID.
     */
    public URI getArtifactIdFromContract(Contract request) {
        final var obligations = request.getObligation();
        final var permissions = request.getPermission();
        final var prohibitions = request.getProhibition();

        if (obligations != null && !obligations.isEmpty())
            return obligations.get(0).getTarget();

        if (permissions != null && !permissions.isEmpty())
            return permissions.get(0).getTarget();

        if (prohibitions != null && !prohibitions.isEmpty())
            return prohibitions.get(0).getTarget();

        return null;
    }

    /**
     * Finds and returns the response type for a given IDS message header.
     * @param header the header
     * @return the response type or null, if no matching type was found
     */
    public ResponseType getResponseType(String header) {
        try {
            serializerProvider.getSerializer().deserialize(header, AccessTokenResponseMessage.class);
            return ResponseType.ACCESS_TOKEN_RESPONSE;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, AppRegistrationResponseMessage.class);
            return ResponseType.APP_REGISTRATION_RESPONSE;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, ArtifactResponseMessage.class);
            return ResponseType.ARTIFACT_RESPONSE;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, ContractAgreementMessage.class);
            return ResponseType.CONTRACT_AGREEMENT;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, ContractResponseMessage.class);
            return ResponseType.CONTRACT_RESPONSE;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, DescriptionResponseMessage.class);
            return ResponseType.DESCRIPTION_RESPONSE;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, OperationResultMessage.class);
            return ResponseType.OPERATION_RESULT;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, ParticipantResponseMessage.class);
            return ResponseType.PARTICIPANT_RESPONSE;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, RejectionMessage.class);
            return ResponseType.REJECTION;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, ContractRejectionMessage.class);
            return ResponseType.CONTRACT_REJECTION;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, ResultMessage.class);
            return ResponseType.RESULT;
        } catch (IOException ignored) { }

        try {
            serializerProvider.getSerializer().deserialize(header, UploadResponseMessage.class);
            return ResponseType.UPLOAD_RESPONSE;
        } catch (IOException ignored) { }

        return null;
    }

    /**
     * Enum of possible response types of IDS message headers.
     */
    public enum ResponseType {
        ACCESS_TOKEN_RESPONSE("ACCESS_TOKEN_RESPONSE"),
        APP_REGISTRATION_RESPONSE("APP_REGISTRATION_RESPONSE"),
        ARTIFACT_RESPONSE("ARTIFACT_RESPONSE"),
        CONTRACT_AGREEMENT("CONTRACT_AGREEMENT"),
        CONTRACT_RESPONSE("CONTRACT_RESPONSE"),
        DESCRIPTION_RESPONSE("DESCRIPTION_RESPONSE"),
        OPERATION_RESULT("OPERATION_RESULT"),
        PARTICIPANT_RESPONSE("PARTICIPANT_RESPONSE"),
        REJECTION("REJECTION"),
        CONTRACT_REJECTION("CONTRACT_REJECTION"),
        RESULT("RESULT"),
        UPLOAD_RESPONSE("UPLOAD_RESPONSE");

        private final String type;

        ResponseType(String string) {
            type = string;
        }

        @Override
        public String toString() {
            return type;
        }
    }
}
