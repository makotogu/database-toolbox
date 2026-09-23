const assert = require('node:assert/strict');
const fs = require('node:fs');
(async () => {
  const {connectionKind, parseConnectionUrl: parse, buildConnectionUrl: build} = await import('data:text/javascript;base64,' + fs.readFileSync(__dirname+'/../src/main/resources/static/workbench/connection-fields.js').toString('base64'));
  assert.equal(connectionKind('org.opengauss.Driver'), null);
  for (const kind of ['mysql', 'postgresql']) {
    for (const host of ['localhost', '127.0.0.1', '[::1]']) {
      const fields = {host, port:'5432', database:'中文 库/?:@&'};
      assert.deepEqual(parse(kind, build(kind, fields)), fields);
    }
    for (const url of [`jdbc:${kind}://host/db?password=secret`, `jdbc:${kind}://a,b/db`, `jdbc:${kind}://user@host/db`, `jdbc:${kind}://host/db?x=<saved>`, `jdbc:${kind}://host/%XX`]) assert.equal(parse(kind, url), null);
    for (const host of ['host/path', 'user@host', 'a,b']) assert.throws(() => build(kind, {host,port:'3306',database:'db'}));
    for (const port of ['0', '65536', 'abc']) assert.throws(() => build(kind, {host:'localhost',port,database:'db'}));
    assert.throws(() => build(kind,{host:'localhost',port:'5432',database:''}));
  }
  console.log('PASS connection URL round trips, encoding, driver boundaries and invalid fields');
})().catch(e => {console.error(e);process.exitCode=1;});
