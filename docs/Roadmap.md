# Roadmap

Planned work that is blocked or not started yet.

## Sandboxing of `prepare` scripts

The [`prepare` scripts](Repository_Config.md#prepare) of repositories run with the permissions of Technolinator, they are **not sandboxed**: they are expected to work in their own checkout, but nothing enforces that.
Not running them for pull-requests of forks, filtering their environment and checking what they leave behind are the only protections today.

Intended is to confine them with [Landlock](https://landlock.io), a Linux kernel feature that lets an unprivileged process restrict its own file access, via the small wrapper [`landrun`](https://github.com/Zouuup/landrun).
Container sandboxes like bubblewrap or nsjail need user namespaces or `CAP_SYS_ADMIN`, which the default seccomp profile of containers doesn't grant, while the system calls of Landlock are allowed by the default profiles of Docker and containerd.

### Blocked: Landlock isn't available on GKE

Technolinator runs on Google Kubernetes Engine, whose default node OS is Container-Optimized OS (COS), also the only one of Autopilot clusters. As of November 2026:

| Node image                         | Kernel | Landlock                                                                  |
|------------------------------------|--------|---------------------------------------------------------------------------|
| COS 121 LTS                        | 6.6    | no (inferred)                                                             |
| COS 125 LTS, COS 129 LTS           | 6.12   | no, `# CONFIG_SECURITY_LANDLOCK is not set`                               |
| COS 133 LTS                        | 6.18   | no, `# CONFIG_SECURITY_LANDLOCK is not set`                               |
| COS 138 (development)              | 6.18   | yes, `CONFIG_SECURITY_LANDLOCK=y`, `landlock` in `CONFIG_LSM`             |
| Ubuntu node pools (Standard only)  | Ubuntu | very likely, not verified                                                 |
| GKE Sandbox (gVisor)               | gVisor | only the first version of Landlock, which is too limited                  |

Whether a node supports it can be seen by `landlock` being part of `cat /sys/kernel/security/lsm` on the node.
The work starts as soon as a COS LTS with Landlock is rolled out to the GKE release channel in use. Sources: the [COS kernel configurations](https://cos.googlesource.com/third_party/kernel/+refs).

### Planned rework

* Setting `APP_PREPARE_SANDBOXING` (`landlock` by default, or `disabled`), a startup check of the kernel, and failing preparations with an explanatory error if Landlock is selected but not available. Never running unsandboxed silently.
* `landrun`, pinned and checksum-verified, in the image.
* Access of the script:

  | Access                     | Paths                                                                  |
  |----------------------------|------------------------------------------------------------------------|
  | read, write, execute       | the checkout and the scratch directory of the analysis                 |
  | read, execute              | `/usr`, `/bin`, `/lib*`, `/etc`, `/app` (tools and SDKs)               |
  | read                       | `/proc`                                                                |
  | read, write                | `/dev/null`, `/dev/zero`, `/dev/urandom`                               |
  | none                       | `/data` and the rest of `/tmp` (other checkouts, caches, credentials)  |

* Network access stays unrestricted, as package repositories have to be reachable. Isolation is up to the runtime environment (e.g. network policies). Restricting outgoing connections to certain ports is possible with Linux 6.7 and newer.
* Linux 6.12 and newer additionally keep the script from sending signals to Technolinator.
* Landlock's checks on process access would also keep sandboxed processes from reading `/proc/<pid>/environ` of Technolinator, which holds its secrets.
  Today, processes started by Technolinator (cdxgen and the build tools as well as the scripts) run as the same user and might be able to read that. This should be verified inside of a container (`cat /proc/1/environ`), and is a reason to sandbox cdxgen as well.
