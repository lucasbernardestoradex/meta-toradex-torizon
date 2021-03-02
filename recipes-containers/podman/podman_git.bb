HOMEPAGE = "https://podman.io/"
SUMMARY =  "A daemonless container engine"
DESCRIPTION = "Podman is a daemonless container engine for developing, \
    managing, and running OCI Containers on your Linux System. Containers can \
    either be run as root or in rootless mode. Simply put: \
    `alias docker=podman`. \
    "

DEPENDS = " \
    go-metalinter-native \
    go-md2man-native \
    gpgme \
    libseccomp \
    ${@bb.utils.filter('DISTRO_FEATURES', 'systemd', d)} \
"

python __anonymous() {
    msg = ""
    # ERROR: Nothing PROVIDES 'libseccomp' (but meta-virtualization/recipes-containers/podman/ DEPENDS on or otherwise requires it).
    # ERROR: Required build target 'meta-world-pkgdata' has no buildable providers.
    # Missing or unbuildable dependency chain was: ['meta-world-pkgdata', 'podman', 'libseccomp']
    if 'security' not in d.getVar('BBFILE_COLLECTIONS').split():
        msg += "Make sure meta-security should be present as it provides 'libseccomp'"
        raise bb.parse.SkipRecipe(msg)
}

SRCREV = "a0d478edea7f775b7ce32f8eb1a01e75374486cb"
SRC_URI = " \
    git://github.com/containers/libpod.git;branch=v2.2;protocol=https \
    file://0001-Makefile-split-install.docker-docs-from-install.dock.patch \
    file://0001-Makefile-install-systemd-services-conditionally.patch \
    file://0001-Makefile-avoid-building-podman-podman-remote-during-.patch \
"

LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://src/import/LICENSE;md5=e3fc50a88d0a364313df4b21ef20c29e"

GO_IMPORT = "import"

S = "${WORKDIR}/git"

PV = "2.2.1+git${SRCPV}"

PACKAGES =+ "${PN}-contrib"

PODMAN_PKG = "github.com/containers/libpod"
BUILDTAGS ?= "seccomp varlink \
${@bb.utils.contains('DISTRO_FEATURES', 'systemd', 'systemd', '', d)} \
exclude_graphdriver_btrfs exclude_graphdriver_devicemapper"

# overide LDFLAGS to allow podman to build without: "flag provided but not # defined: -Wl,-O1
export LDFLAGS=""

inherit go goarch
inherit systemd pkgconfig

do_configure[noexec] = "1"

EXTRA_OEMAKE = " \
     PREFIX=${prefix} BINDIR=${bindir} LIBEXECDIR=${libexecdir} \
     ETCDIR=${sysconfdir} TMPFILESDIR=${nonarch_libdir}/tmpfiles.d \
     SYSTEMDDIR=${systemd_unitdir}/system USERSYSTEMDDIR=${systemd_unitdir}/user \
"

# remove 'docker' from the packageconfig if you don't want podman to
# build and install the docker wrapper. If docker is enabled in the
# packageconfig, the podman package will rconfict with docker.
PACKAGECONFIG ?= "docker"
PACKAGECONFIG[docs] = ",,,"
PACKAGECONFIG[docker] = ",,,"

DEFAULT_MAKE_TARGET ?= "${@bb.utils.contains('PACKAGECONFIG','docs','all','binaries',d)}"
DEFAULT_INSTALL_TARGET ?= "${@bb.utils.contains('PACKAGECONFIG','docs','install','install.bin install.remote install.cni install.systemd',d)}"

do_compile() {
	cd ${S}/src
	rm -rf .gopath
	mkdir -p .gopath/src/"$(dirname "${PODMAN_PKG}")"
	ln -sf ../../../../import/ .gopath/src/"${PODMAN_PKG}"

	ln -sf "../../../import/vendor/github.com/varlink/" ".gopath/src/github.com/varlink"

	export GOARCH="${BUILD_GOARCH}"
	export GOPATH="${S}/src/.gopath"
	export GOROOT="${STAGING_DIR_NATIVE}/${nonarch_libdir}/${HOST_SYS}/go"

	cd ${S}/src/.gopath/src/"${PODMAN_PKG}"

	oe_runmake pkg/varlink/iopodman.go GO=go

	chmod -R u+w ${S}/src/.gopath/pkg/mod/github.com/varlink

	# Pass the needed cflags/ldflags so that cgo
	# can find the needed headers files and libraries
	export GOARCH=${TARGET_GOARCH}
	export CGO_ENABLED="1"
	export CGO_CFLAGS="${CFLAGS} --sysroot=${STAGING_DIR_TARGET}"
	export CGO_LDFLAGS="${LDFLAGS} --sysroot=${STAGING_DIR_TARGET}"

	oe_runmake BUILDTAGS="${BUILDTAGS}" ${DEFAULT_MAKE_TARGET}
}

do_install() {
	cd ${S}/src/.gopath/src/"${PODMAN_PKG}"

	export GOARCH="${BUILD_GOARCH}"
	export GOPATH="${S}/src/.gopath"
	export GOROOT="${STAGING_DIR_NATIVE}/${nonarch_libdir}/${HOST_SYS}/go"

	oe_runmake ${DEFAULT_INSTALL_TARGET} DESTDIR="${D}"
	if ${@bb.utils.contains('PACKAGECONFIG', 'docker', 'true', 'false', d)}; then
		oe_runmake install.docker DESTDIR="${D}"
		if ${@bb.utils.contains('PACKAGECONFIG', 'docs', 'true', 'false', d)}; then
			oe_runmake install.docker-docs DESTDIR="${D}"
		fi
	fi
}

FILES_${PN} += " \
    ${systemd_unitdir}/system/* \
    ${systemd_unitdir}/user/* \
    ${nonarch_libdir}/tmpfiles.d/* \
    ${sysconfdir}/cni \
"

SYSTEMD_SERVICE_${PN} = "podman.service podman.socket"

RDEPENDS_${PN} += "conmon virtual/runc iptables cni skopeo"
RRECOMMENDS_${PN} += "slirp4netns"
RCONFLICTS_${PN} = "${@bb.utils.contains('PACKAGECONFIG', 'docker', 'docker', '', d)}"
