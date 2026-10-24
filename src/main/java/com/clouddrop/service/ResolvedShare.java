package com.clouddrop.service;

import com.clouddrop.entity.ShareLink;
import com.clouddrop.entity.StoredFile;

public record ResolvedShare(ShareLink link, StoredFile file) {
}
