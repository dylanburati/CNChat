const stdOut = document.getElementById('stdout');

function println(line) {
  stdOut.textContent += line + '\n';
}

chatClientBegin({}, 'http://localhost:8081', 'join a')
  .then(session => {
    println(session.uuid);  
  });


