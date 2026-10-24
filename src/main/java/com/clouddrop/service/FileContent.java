package com.clouddrop.service;

import com.clouddrop.entity.StoredFile;
import java.io.InputStream;

/** A file's metadata plus an open stream of its bytes. Whoever receives this must close the stream. */
public record FileContent(StoredFile file, InputStream stream) {
}
