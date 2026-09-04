import paramiko
import os
import sys
import time

HOST = "52.231.34.104"
PORT = 22
USERNAME = "azureadmin"
PASSWORD = "1Rhcemdtla!3%7"
REMOTE_DIR = "/home/azureadmin/csp-infra"
JAR_NAME = "cloud-infra-admin-0.0.1-SNAPSHOT.jar"
JAR_CANDIDATES = [
    os.path.join(os.path.dirname(__file__), "backend", "build", "libs", JAR_NAME),
    os.path.join(os.path.dirname(__file__), "backend", "backend", "build", "libs", JAR_NAME),
    os.path.join(os.path.dirname(__file__), "build", "libs", JAR_NAME)
]

RESTART_CMD = "sudo systemctl restart infra-admin.service"
STATUS_CMD = "sudo systemctl status infra-admin.service --no-pager"

def log(msg):
    print(f"[DEPLOY] {msg}", flush=True)

LOCAL_JAR = None

def deploy():
    global LOCAL_JAR
    LOCAL_JAR = r"c:\JetBrains\workspace\cloud-infra-admin\backend\build\libs\cloud-infra-admin-0.0.1-SNAPSHOT.jar"
    if not os.path.exists(LOCAL_JAR):
        for cand in JAR_CANDIDATES:
            if os.path.exists(cand):
                LOCAL_JAR = cand
                break

    if not LOCAL_JAR or not os.path.exists(LOCAL_JAR):
        log(f"ERROR: JAR not found in candidates: {JAR_CANDIDATES}")
        sys.exit(1)

    local_size = os.path.getsize(LOCAL_JAR)
    jar_size_mb = local_size / (1024 * 1024)
    log(f"Local JAR file: {LOCAL_JAR} ({jar_size_mb:.1f} MB, {local_size} bytes)")

    # SSH client setup
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

    log(f"Connecting to {HOST}:{PORT} as {USERNAME}...")
    client.connect(HOST, port=PORT, username=USERNAME, password=PASSWORD, timeout=60)
    log("Connected successfully!")

    remote_path = f"{REMOTE_DIR}/{JAR_NAME}"
    remote_backup = f"{REMOTE_DIR}/{JAR_NAME}.bak"

    # Backup existing file
    stdin, stdout, stderr = client.exec_command(f"[ -f {remote_path} ] && cp -f {remote_path} {remote_backup} && echo 'Backup OK' || echo 'No existing file'")
    log(stdout.read().decode().strip())

    # SCP Upload
    log(f"Uploading JAR to {remote_path} ...")
    sftp = client.open_sftp()

    last_pct = [-10]
    def progress(transferred, total_size):
        pct = int((transferred / total_size) * 100)
        if pct >= last_pct[0] + 10 or transferred == total_size:
            last_pct[0] = pct
            mb = transferred / (1024 * 1024)
            log(f"Uploading... {mb:.1f}/{total_size/(1024*1024):.1f} MB ({pct}%)")

    sftp.put(LOCAL_JAR, remote_path, callback=progress)
    sftp.close()
    log("Upload complete!")

    # Verify remote file size
    stdin, stdout, stderr = client.exec_command(f"stat -c %s {remote_path}")
    remote_size_str = stdout.read().decode().strip()
    remote_size = int(remote_size_str) if remote_size_str.isdigit() else 0
    log(f"Verified remote file size: {remote_size} bytes (Local: {local_size} bytes)")

    if remote_size != local_size:
        log(f"ERROR: File size mismatch! Remote: {remote_size} bytes, Local: {local_size} bytes")
        log("Restoring backup...")
        client.exec_command(f"cp -f {remote_backup} {remote_path}")
        client.close()
        sys.exit(1)

    # Permissions
    client.exec_command(f"chmod 755 {remote_path}")

    # Service Restart
    log(f"Restarting service: {RESTART_CMD}")
    stdin, stdout, stderr = client.exec_command(RESTART_CMD)
    stdout.channel.recv_exit_status()
    err = stderr.read().decode().strip()
    if err:
        log(f"Restart stderr: {err}")

    log("Waiting 10 seconds for service startup...")
    time.sleep(10)

    # Service Status Check
    log("Checking service status...")
    stdin, stdout, stderr = client.exec_command(STATUS_CMD)
    status_output = stdout.read().decode()
    print(status_output, flush=True)

    if "active (running)" in status_output:
        log("SUCCESS: Service is running successfully!")
    else:
        log("WARNING: Service is not active. Checking recent logs...")
        stdin, stdout, stderr = client.exec_command("sudo journalctl -u infra-admin.service -n 30 --no-pager")
        print(stdout.read().decode(), flush=True)

    client.close()

if __name__ == "__main__":
    deploy()
