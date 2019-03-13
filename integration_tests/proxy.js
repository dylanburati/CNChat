const fs = require('fs');
const http = require('http');
const https = require('https');

const options = {
  key: fs.readFileSync(process.env.HOME + '/cert.key'),
  cert: fs.readFileSync(process.env.HOME + '/cert.crt')
};

const proxyOptions = {
  method: 'POST'
};

https.createServer(options, (req, res) => {
  let reqData = '';
  let proxyResData = '';
  const proxyReq = http.request('http://localhost:8081', proxyOptions, proxyRes => {
    proxyRes.on('data', d => {
      console.log('> ' + d);
      proxyResData += d;
    });
    proxyRes.on('end', () => {
      res.writeHead(200, {
        'Access-Control-Allow-Origin': '*',
        'Content-Type': 'application/json'
      });
      res.write(JSON.stringify({ data: proxyResData }));
      res.end();
    });
  });

  req.on('data', d => {
    console.log('< ' + d);
    reqData += d;
  });
  req.on('end', () => {
    proxyReq.write(reqData);
    proxyReq.end();
  });
}).listen(8083);
