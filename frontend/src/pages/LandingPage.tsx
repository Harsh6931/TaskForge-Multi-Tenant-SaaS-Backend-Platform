import { Layers, Zap, Shield, Users, ArrowRight, ExternalLink } from "lucide-react";

const features = [
  {
    icon: Shield,
    title: "Tenant Isolation",
    description:
      "PostgreSQL Row-Level Security ensures zero cross-tenant data leakage — even with accidental queries.",
  },
  {
    icon: Users,
    title: "Role-Based Access",
    description:
      "Fine-grained RBAC with ADMIN, MANAGER, MEMBER, and VIEWER roles per workspace.",
  },
  {
    icon: Zap,
    title: "AI-Powered Search",
    description:
      "Semantic search over tasks via embeddings and pgvector cosine similarity.",
  },
  {
    icon: Layers,
    title: "Real-Time Collaboration",
    description:
      "Live task board updates via STOMP WebSocket, fanned out with Redis pub/sub.",
  },
];

export default function LandingPage() {
  return (
    <div className="min-h-screen bg-gray-950 text-gray-100 flex flex-col">
      {/* ── Navbar ── */}
      <nav className="border-b border-white/10 backdrop-blur-sm sticky top-0 z-50 bg-gray-950/80">
        <div className="max-w-6xl mx-auto px-6 h-16 flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-brand-500 flex items-center justify-center">
              <Layers size={18} className="text-white" />
            </div>
            <span className="font-bold text-lg tracking-tight text-white">
              TaskForge
            </span>
          </div>

          <div className="flex items-center gap-3">
            <a
              href="https://github.com"
              target="_blank"
              rel="noreferrer"
              className="btn-ghost"
            >
              <ExternalLink size={16} />
              GitHub
            </a>
            <button id="get-started-btn" className="btn-primary">
              Get Started
              <ArrowRight size={15} />
            </button>
          </div>
        </div>
      </nav>

      {/* ── Hero ── */}
      <main className="flex-1">
        <section className="relative overflow-hidden py-32 px-6">
          {/* Glow blobs */}
          <div className="absolute inset-0 pointer-events-none">
            <div className="absolute top-1/3 left-1/4 w-96 h-96 bg-brand-500/20 rounded-full blur-3xl" />
            <div className="absolute bottom-1/4 right-1/4 w-80 h-80 bg-violet-600/15 rounded-full blur-3xl" />
          </div>

          <div className="relative max-w-4xl mx-auto text-center animate-fade-in">
            <div className="inline-flex items-center gap-2 rounded-full border border-brand-500/30 bg-brand-500/10 px-4 py-1.5 text-sm text-brand-300 mb-8">
              <span className="w-1.5 h-1.5 rounded-full bg-brand-400 animate-pulse" />
              Multi-Tenant SaaS Backend Platform
            </div>

            <h1 className="text-5xl md:text-7xl font-extrabold tracking-tight text-white mb-6 leading-tight">
              Ship faster,{" "}
              <span className="text-transparent bg-clip-text bg-gradient-to-r from-brand-400 to-violet-400">
                scale fearlessly
              </span>
            </h1>

            <p className="text-xl text-gray-400 max-w-2xl mx-auto mb-10 leading-relaxed">
              TaskForge is a production-grade multi-tenant project management
              platform with JWT auth, Postgres RLS, AI semantic search, and
              real-time collaboration built in.
            </p>

            <div className="flex flex-col sm:flex-row gap-4 justify-center">
              <button id="hero-cta-btn" className="btn-primary text-base px-6 py-3">
                View Dashboard
                <ArrowRight size={18} />
              </button>
              <a
                id="hero-docs-link"
                href="/docs"
                className="btn-ghost text-base px-6 py-3 border border-white/10 rounded-lg"
              >
                Read the Docs
              </a>
            </div>
          </div>
        </section>

        {/* ── Tech Stack Badges ── */}
        <section className="py-10 border-y border-white/5">
          <div className="max-w-6xl mx-auto px-6">
            <p className="text-center text-sm text-gray-500 mb-6 uppercase tracking-widest font-medium">
              Built with
            </p>
            <div className="flex flex-wrap justify-center gap-3">
              {[
                "Spring Boot 3",
                "Java 21",
                "PostgreSQL 16 + pgvector",
                "Redis",
                "FastAPI",
                "React 18",
                "TypeScript",
                "Docker",
              ].map((tech) => (
                <span
                  key={tech}
                  className="badge bg-white/5 border border-white/10 text-gray-300 px-3 py-1.5 text-sm"
                >
                  {tech}
                </span>
              ))}
            </div>
          </div>
        </section>

        {/* ── Features ── */}
        <section className="py-24 px-6">
          <div className="max-w-6xl mx-auto">
            <div className="text-center mb-16">
              <h2 className="text-3xl md:text-4xl font-bold text-white mb-4">
                Enterprise features, developer-first DX
              </h2>
              <p className="text-gray-400 max-w-xl mx-auto">
                Every layer of TaskForge is designed for real-world production
                scenarios — not just demos.
              </p>
            </div>

            <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-6">
              {features.map(({ icon: Icon, title, description }) => (
                <div
                  key={title}
                  className="card group hover:border-brand-500/30 hover:bg-brand-500/5 transition-all duration-200 animate-slide-up"
                >
                  <div className="w-10 h-10 rounded-lg bg-brand-500/15 flex items-center justify-center mb-4 group-hover:bg-brand-500/25 transition-colors">
                    <Icon size={20} className="text-brand-400" />
                  </div>
                  <h3 className="font-semibold text-white mb-2">{title}</h3>
                  <p className="text-sm text-gray-400 leading-relaxed">
                    {description}
                  </p>
                </div>
              ))}
            </div>
          </div>
        </section>
      </main>

      {/* ── Footer ── */}
      <footer className="border-t border-white/5 py-8 px-6">
        <div className="max-w-6xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-4 text-sm text-gray-500">
          <div className="flex items-center gap-2">
            <div className="w-5 h-5 rounded bg-brand-500/80 flex items-center justify-center">
              <Layers size={11} className="text-white" />
            </div>
            <span>TaskForge</span>
          </div>
          <span>Phase 0 — Scaffold complete. More features coming soon.</span>
        </div>
      </footer>
    </div>
  );
}
