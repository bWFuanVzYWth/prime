//! Closed blacklist of events allowed to discard whole-image temporal history.
//! Content revisions, animation phases, sample indices and skipped frames are absent:
//! they use current-scene updates and local support checks instead.

#[derive(Clone, Copy)]
pub(super) enum Backend {
    Restir,
    Rr,
}
impl Backend {
    fn name(self) -> &'static str {
        match self {
            Self::Restir => "ReSTIR",
            Self::Rr => "RR",
        }
    }
    fn reset_scope(self) -> &'static str {
        match self {
            Self::Restir => "restir.history.reset",
            Self::Rr => "rr.history.reset",
        }
    }
    fn storage_scope(self) -> &'static str {
        match self {
            Self::Restir => "restir.history.storage",
            Self::Rr => "rr.history.storage",
        }
    }
}

/// Explicit domain replacement or an unusable RR feature. Adding an event requires
/// proving why current-scene replay/local rejection cannot retain this domain.
#[derive(Clone, Copy)]
pub(super) enum GlobalReset {
    WorldReplaced,
    WorldEpochChanged,
    SceneDomainReplaced,
    RenderDomainChanged,
    InputExtentChanged,
    RrFeatureReconfigured,
    RrEvaluationFailed,
}
impl GlobalReset {
    fn reason(self) -> &'static str {
        match self {
            Self::WorldReplaced => "world",
            Self::WorldEpochChanged => "world_epoch",
            Self::SceneDomainReplaced => "scene_owner",
            Self::RenderDomainChanged => "render_domain",
            Self::InputExtentChanged => "input_extent",
            Self::RrFeatureReconfigured => "configuration",
            Self::RrEvaluationFailed => "evaluation_failure",
        }
    }
}

/// Actual storage lifetime transitions, independent of the diagnostic override.
#[derive(Clone, Copy)]
pub(super) enum StorageCold {
    WorldReleased,
    RenderDomainReleased,
    InputExtentChanged,
    RrSetupFailed,
    RrFeatureReconfigured,
    RrEvaluationFailed,
}
impl StorageCold {
    fn reason(self) -> &'static str {
        match self {
            Self::WorldReleased => "world_release",
            Self::RenderDomainReleased => "render_domain_release",
            Self::InputExtentChanged => "input_extent",
            Self::RrSetupFailed => "sdk_setup_failure",
            Self::RrFeatureReconfigured => "sdk_configuration",
            Self::RrEvaluationFailed => "sdk_failure",
        }
    }
}

pub(super) fn record_global(backend: Backend, event: GlobalReset, valid: bool, ignored: bool) {
    let reason = event.reason();
    eprintln!(
        "[Prime PT] {} history: event={reason} action={} valid={valid}",
        backend.name(),
        if ignored { "ignored" } else { "reset" },
    );
    let mut span = prime_diagnostics::scope(backend.reset_scope());
    span.value("reason", reason);
    span.count("ignored", u64::from(ignored));
    span.count("valid", u64::from(valid));
}

pub(super) fn record_storage(backend: Backend, event: StorageCold, valid: bool) {
    let reason = event.reason();
    eprintln!(
        "[Prime PT] {} history: event={reason} action=storage_cold valid={valid}",
        backend.name(),
    );
    let mut span = prime_diagnostics::scope(backend.storage_scope());
    span.value("reason", reason);
    span.count("valid", u64::from(valid));
}
