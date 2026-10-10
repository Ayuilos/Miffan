DEPS_DIR ?= /Users/chenxiansheng/miffan/.deps/p6a
OPENSSL_DIR ?= /opt/homebrew/opt/openssl@3
CC := clang
CPPFLAGS := -I. -Ibuild/src/common-c/src -Ibuild/src/common-c/enet/include \
 -Ibuild/src/common-c/nanors -Ibuild/src/common-c/nanors/deps/obl \
 -Ibuild/src/libgamestream -I$(OPENSSL_DIR)/include \
 -DHAS_SOCKLEN_T=1 -DHAS_FCNTL=1 -DHAS_IOCTL=1 -DHAS_POLL=1 \
 -DHAS_GETADDRINFO=1 -DHAS_GETNAMEINFO=1 -DHAS_INET_PTON=1 \
 -DHAS_INET_NTOP=1 -DHAS_MSGHDR_FLAGS=1 -DNDEBUG
CFLAGS := -O2 -g -std=c11 -Wall -Wextra -Wno-unused-parameter
LDFLAGS := -L$(OPENSSL_DIR)/lib -Wl,-rpath,$(OPENSSL_DIR)/lib
LDLIBS := -lssl -lcrypto -lcurl -lexpat -lpthread
COMMON := $(notdir $(wildcard $(DEPS_DIR)/common-c/src/*.c))
ENET := callbacks compress host list packet peer protocol unix
NANORS := rs deps/obl/oblas_common deps/obl/oblas_lite
GAME := client http mkcert xml
OBJS := $(addprefix build/obj/common/,$(COMMON:.c=.o)) \
 $(addprefix build/obj/enet/,$(addsuffix .o,$(ENET))) \
 $(addprefix build/obj/nanors/,$(addsuffix .o,$(NANORS))) \
 $(addprefix build/obj/game/,$(addsuffix .o,$(GAME))) \
 build/obj/probe.o build/obj/transport.o
PATCHES := $(wildcard patches/*.patch)

.PHONY: all clean check prepare
all: build/p6a-probe
prepare: build/src/.prepared
build/src/.prepared: prepare.py $(PATCHES)
	python3 prepare.py "$(DEPS_DIR)" build/src patches
	touch $@
build/obj/common/%.o: build/src/.prepared
	@mkdir -p $(@D)
	$(CC) $(CPPFLAGS) $(CFLAGS) -Werror -c build/src/common-c/src/$*.c -o $@
build/obj/enet/%.o: build/src/.prepared
	@mkdir -p $(@D)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c build/src/common-c/enet/$*.c -o $@
build/obj/nanors/%.o: build/src/.prepared
	@mkdir -p $(@D)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c build/src/common-c/nanors/$*.c -o $@
build/obj/game/%.o: build/src/.prepared transport.h
	@mkdir -p $(@D)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c build/src/libgamestream/$*.c -o $@
build/obj/%.o: %.c transport.h build/src/.prepared
	@mkdir -p $(@D)
	$(CC) $(CPPFLAGS) $(CFLAGS) -Werror -c $< -o $@
build/p6a-probe: $(OBJS)
	$(CC) $(LDFLAGS) $^ $(LDLIBS) -o $@
check: build/p6a-probe
	OPENSSL_DIR="$(OPENSSL_DIR)" python3 -u offline_test.py
clean:
	rm -rf build
