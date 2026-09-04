# Isolated local test fixture only; never deploy this image as an application.
FROM debian:bullseye-slim
RUN apt-get update && apt-get install -y --no-install-recommends openssh-server python3 && rm -rf /var/lib/apt/lists/* && mkdir -p /run/sshd /root/.ssh && chmod 700 /root/.ssh && passwd -d root && ssh-keygen -A
CMD ["/usr/sbin/sshd", "-D", "-e", "-o", "PasswordAuthentication=no", "-o", "PermitRootLogin=prohibit-password", "-o", "UsePAM=no", "-o", "AllowStreamLocalForwarding=yes"]
