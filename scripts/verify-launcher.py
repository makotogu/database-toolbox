#!/usr/bin/env python3
"""Exercise the POSIX launcher against a disposable JAR/data directory (Python 3 + Java 8).
The test creates H2 fixtures, starts/stops only its own processes and leaves evidence in /tmp.
Desktop opening is captured by fixtures; it never opens the user's actual browser.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', required=True)
    p.add_argument('--java-home', required=True)
    args = p.parse_args()
    root = Path(tempfile.mkdtemp(prefix='toolbox launcher 中文 '))
    app, other, bins = root/'工作台 app', root/'另一个实例', root/'bin'
    for directory in (app, other, bins):
        directory.mkdir()
    script = Path(__file__).resolve().with_name('database-toolbox.sh')
    for directory in (app, other):
        shutil.copy2(args.jar, directory/'database-toolbox.jar')
        shutil.copy2(script, directory/'database-toolbox.sh')
    capture = root/'opened.txt'
    for name in ('open', 'xdg-open'):
        wrapper = bins/name
        wrapper.write_text('#!/bin/sh\n"$TEST_PYTHON" -c \'import os,sys,urllib.request; urllib.request.urlopen(sys.argv[1]+"/api/bootstrap"); open(os.environ["TEST_CAPTURE"],"a").write(sys.argv[1]+"\\n")\' "$1"\nexit "${TEST_OPEN_EXIT:-0}"\n')
        wrapper.chmod(0o700)
    (bins/'uname').write_text('#!/bin/sh\nprintf "%s\\n" "$TEST_SYSTEM"\n')
    (bins/'uname').chmod(0o700)
    port = free_port()
    base = 'http://127.0.0.1:'+str(port)
    env = dict(os.environ, JAVA_HOME=str(Path(args.java_home).resolve()), TOOLBOX_PORT=str(port),
               TOOLBOX_OPEN_BROWSER='auto', DISPLAY='', WAYLAND_DISPLAY='', SSH_TTY='', SSH_CONNECTION='',
               TEST_SYSTEM='Linux', TEST_CAPTURE=str(capture), TEST_PYTHON=sys.executable,
               PATH=str(bins)+os.pathsep+os.environ['PATH'])
    env.pop('TOOLBOX_HOME', None)
    env.pop('TOOLBOX_DATA_DIR', None)
    checks = []
    token = None

    def check(name):
        checks.append(name)
        print('PASS '+name, flush=True)

    def run(action, directory=app, expected=0, **overrides):
        result = subprocess.run(['sh', str(directory/'database-toolbox.sh'), action], env=dict(env, **overrides),
                                cwd=str(root), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=45)
        with (root/'commands.log').open('a') as log:
            log.write(action+'\n'+result.stdout+'\n')
        assert result.returncode == expected, (action, result.returncode, result.stdout)
        return result.stdout

    def api(method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        headers = {'Content-Type':'application/json', 'X-Toolbox-Token':token or ''}
        with urllib.request.urlopen(urllib.request.Request(base+'/api'+path, data, headers, method=method), timeout=10) as response:
            result = json.load(response)
        assert result['success'], result
        return result['data']

    def execute(session, sql):
        request = dict(sessionId=session, sql=sql, mode='SCRIPT', requestId=str(uuid.uuid4()))
        request['confirmationToken'] = api('POST', '/executions/prepare', request)['confirmationToken']
        result = api('POST', '/executions', request)
        for _ in range(100):
            if result['state'] not in ('RUNNING', 'QUEUED'):
                break
            time.sleep(.05)
            result = api('GET', '/executions/'+result['id'])
        assert result['state'] == 'SUCCEEDED', result
        return result

    try:
        assert 'TOOLBOX_OPEN_BROWSER' in run('--help')
        assert 'JAVA_HOME' in run('start', expected=1, JAVA_HOME=str(root/'missing-java'))
        assert 'TOOLBOX_PORT' in run('start', expected=1, TOOLBOX_PORT='0')
        assert 'TOOLBOX_OPEN_BROWSER' in run('start', expected=1, TOOLBOX_OPEN_BROWSER='invalid')
        check('help and actionable Java/port/browser-option validation')
        assert '无桌面' in run('start')
        token = api('GET', '/bootstrap')['token']
        assert not capture.exists()
        pid = (app/'run/database-toolbox.pid').read_text()
        assert '已在运行' in run('start', TOOLBOX_OPEN_BROWSER='1')
        assert (app/'run/database-toolbox.pid').read_text() == pid
        assert str(port) in run('status', TOOLBOX_PORT='1')
        check('Chinese and space paths, headless start, duplicate start, recorded status')
        assert '端口' in run('start', other, expected=1)
        assert '数据目录已被' in run('start', other, expected=1, TOOLBOX_PORT=str(free_port()), TOOLBOX_DATA_DIR=str(app/'data'))
        assert not capture.exists()
        check('port conflict and storage lock do not open a browser')
        with subprocess.Popen(['sleep', '60']) as unrelated:
            (other/'run/database-toolbox.pid').write_text(str(unrelated.pid)+' 111_222 '+str(port)+'\n')
            run('stop', other)
            assert unrelated.poll() is None
            unrelated.terminate()
        check('stale PID does not terminate an unrelated process')
        driver = next(d for d in api('GET', '/drivers') if d['driverClass']=='org.h2.Driver' and d['bundled'])
        connection = api('POST', '/connections', dict(name='Launcher fixture',driverId=driver['id'],jdbcUrl='jdbc:h2:file:./shutdown-fixture',username='sa'))
        session = api('POST', '/sessions', dict(connectionId=connection['id']))['id']
        execute(session, 'CREATE TABLE T(N INT); INSERT INTO T VALUES(42)')
        api('POST', '/sessions/'+session+'/transaction', dict(action='AUTO_COMMIT',autoCommit=False))
        execute(session, 'INSERT INTO T VALUES(99)')
        run('stop')
        run('status', expected=3)
        run('start', TOOLBOX_OPEN_BROWSER='1', DISPLAY=':fixture')
        token = api('GET', '/bootstrap')['token']
        for _ in range(50):
            if capture.exists(): break
            time.sleep(.1)
        assert capture.read_text().splitlines() == [base]
        session = api('POST', '/sessions', dict(connectionId=connection['id']))['id']
        result = execute(session, 'SELECT N FROM T')
        assert any(r.get('rows')==[[42]] for s in result['statements'] for r in s['results'])
        api('DELETE','/sessions/'+session)
        check('TERM rollback survives restart; opener observes an already-ready service')
        run('restart', TEST_SYSTEM='Darwin', TEST_OPEN_EXIT='1')
        token = api('GET', '/bootstrap')['token']
        run('status')
        for _ in range(50):
            if len(capture.read_text().splitlines())==2: break
            time.sleep(.1)
        assert len(capture.read_text().splitlines()) == 2
        check('macOS opener dispatch and failed opener do not stop the service')
        run('restart', TOOLBOX_OPEN_BROWSER='0')
        assert len(capture.read_text().splitlines()) == 2
        run('restart', SSH_CONNECTION='fixture')
        assert len(capture.read_text().splitlines()) == 2
        check('explicit opt-out and SSH suppress browser opening')
    finally:
        run('stop')
        run('stop', other)
    (root/'verification.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2))
    print(json.dumps(dict(checks=len(checks),evidence=str(root)),ensure_ascii=False))


if __name__ == '__main__':
    main()
