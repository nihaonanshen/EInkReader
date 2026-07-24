pub mod epub;
pub mod txt;

// Re-export for convenience
pub use epub::parse_epub;
pub use txt::parse_txt;
