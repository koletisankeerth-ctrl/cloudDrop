package com.clouddrop.service;

import com.clouddrop.entity.ShareLink;

/** The saved link plus the RAW token. This is the only moment the raw token exists; it is never stored. */
public record CreatedShare(ShareLink link, String rawToken) {
}
