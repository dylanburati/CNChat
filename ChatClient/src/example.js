/* global window document axios */
// Example ChatClient script
//
//

const handlers = {
  conversation_cat(conversation, pastMsgObj, processedArr) {
    // Code to display the message to the screen goes here
    // pastMsgObj.id gives the conversation ID of the message
    //           .from gives the username of the sender
    //           .time gives the timestamp (result of Date.now() when message was sent)
    //           .data gives the typed message
    //           .contentType gives space-separated classes that describe how to render the message
    //               ('markdown' or 'plaintext')

    // must return true if processedArr has been updated, and false otherwise
    return false;
  },

  set_preferences(prefObj) {
    // Code to change the user's preferences goes here
    // Currently the only preference is `markdown: Boolean`,
    // which changes the default formatting of messages *sent* by the user
  },

  user_message(conversation, msgObj, processedArr) {
    // Code to display the message to the screen goes here
    // msgObj.id gives the conversation ID of the message
    //       .from gives the username of the sender
    //       .time gives the timestamp (result of Date.now() when message was sent)
    //       .data gives the typed message
    //       .contentType gives space-separated classes that describe how to render the message
    //           ('markdown' or 'plaintext')

    // must return true if processedArr has been updated, and false otherwise
    return false;
  }
};

let session;
chatClientBegin(handlers).then(s => { session = s; });
