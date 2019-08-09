describe('CNChat test', function() {
  it('Conversation create', function() {
    cy.server();
    cy.route({
      method: 'POST',
      url: 'https://localhost:8083'
    }).as('chat-join');

    cy.visit('https://localhost:8080/index.html');
    cy.window().then((win) => {
      cy.spy(win.console, "log");
      cy.spy(win.console, "error");
    });
    credentials().forEach(u => {
      cy.get('#inputName').type('{selectall}{backspace}' + u.name);
      cy.get('#inputPass').type('{selectall}{backspace}' + u.pass);
      cy.get('#credentialSubmit').click();
      cy.wait('@chat-join');
      cy.contains('conversation_ls', { timeout: 10000 }).should('exist');
      cy.contains('user_message', { timeout: 10000 }).should('exist');
    });
  });
});
