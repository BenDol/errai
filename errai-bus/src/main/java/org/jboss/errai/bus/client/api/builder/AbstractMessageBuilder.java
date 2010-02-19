package org.jboss.errai.bus.client.api.builder;

import org.jboss.errai.bus.client.api.ErrorCallback;
import org.jboss.errai.bus.client.api.Message;
import org.jboss.errai.bus.client.api.MessageCallback;
import org.jboss.errai.bus.client.api.base.ConversationHelper;
import org.jboss.errai.bus.client.framework.MessageBus;
import org.jboss.errai.bus.client.framework.RequestDispatcher;

import static org.jboss.errai.bus.client.api.base.ConversationHelper.createConversationService;
import static org.jboss.errai.bus.client.api.base.ConversationHelper.makeConversational;

/**
 * The <tt>AbstractMessageBuilder</tt> facilitates the building of a message, and ensures that it is created and used
 * properly.
 */
public class AbstractMessageBuilder {
    private final Message message;

    public AbstractMessageBuilder(Message message) {
        this.message = message;
    }

    /**
     * Implements, creates and returns an instance of <tt>MessageBuildSubject</tt>. This is called initially when a
     * new message is created
     *
     * @return the <tt>MessageBuildSubject</tt> with the appropriate fields and functions for the message builder
     */
    public MessageBuildSubject start() {
        final MessageBuildSendableWithReply sendable = new MessageBuildSendableWithReply() {
            boolean reply = false;
            public MessageBuildSendable repliesTo(MessageCallback callback) {
                reply = true;
                makeConversational(message, callback);
                return this;
            }

            public void sendNowWith(MessageBus viaThis) {
                if (reply) createConversationService(viaThis, message);
                message.sendNowWith(viaThis);
            }

            public void sendNowWith(MessageBus viaThis, boolean fireMessageListener) {
                if (reply) createConversationService(viaThis, message);
                viaThis.send(message, false);
            }

            public void sendNowWith(RequestDispatcher viaThis) {
                message.sendNowWith(viaThis);
            }

            public Message getMessage() {
                return message;
            }
        };

        final MessageBuildParms parmBuilder = new MessageBuildParms() {
            public MessageBuildParms with(String part, Object value) {
                message.set(part, value);
                return this;
            }

            public MessageBuildParms with(Enum part, Object value) {
                message.set(part, value);
                return this;
            }

            public MessageBuildParms copy(String part, Message m) {
                message.copy(part, m);
                return this;
            }

            public MessageBuildParms copy(Enum part, Message m) {
                message.copy(part, m);
                return this;
            }

            public MessageBuildParms copyResource(String part, Message m) {
                message.copyResource(part, m);
                return this;
            }

            public MessageBuildSendableWithReply errorsHandledBy(ErrorCallback callback) {
                message.errorsCall(callback);
                return sendable;

            }

            public MessageBuildSendableWithReply noErrorHandling() {
                return sendable;
            }

            public Message getMessage() {
                return message;
            }
        };

        final MessageBuildCommand command = new MessageBuildCommand() {
            public MessageBuildParms command(Enum command) {
                message.command(command);
                return parmBuilder;
            }

            public MessageBuildParms command(String command) {
                message.command(command);
                return parmBuilder;
            }

            public MessageBuildParms signalling() {
                return parmBuilder;
            }

            public Message getMessage() {
                return message;
            }
        };

        return new MessageBuildSubject() {
            public MessageBuildCommand toSubject(String subject) {
                message.toSubject(subject);
                return command;
            }

            public MessageBuildCommand subjectProvided() {
               return command;
            }

            public Message getMessage() {
                return message;
            }
        };
    }
}
