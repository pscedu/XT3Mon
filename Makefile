# $Id$

JAVAC=javac
JFLAGS=-g

APP = XT3Mon.class

.SUFFIXES: .java .class

.java.class:
	${JAVAC} ${JFLAGS} $<

all: ${APP}

clean:
	rm -rf ${APP}

run:
	appletviewer XT3Mon.html
