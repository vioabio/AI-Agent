import { parseResumeText } from "./resumeParser";

export function ResumeDocument({ value }: { value?: string | null }) {
  const blocks = parseResumeText(value ?? "");
  if (!blocks.length) return <div className="resume-document resume-empty">暂无完整简历正文</div>;

  return (
    <article className="resume-document" aria-label="完整简历正文内容">
      {blocks.map((block, index) => {
        if (block.kind === "section") {
          return (
            <section className="resume-content-section" key={`${block.label}-${index}`}>
              <h6 className="resume-section-title">{block.label}</h6>
              {block.text ? <p>{block.text}</p> : null}
            </section>
          );
        }
        if (block.kind === "numbered") {
          return (
            <div className="resume-numbered-item" key={`${block.marker}-${index}`}>
              <span className="resume-item-marker">{block.marker}</span>
              <p>{block.text}</p>
            </div>
          );
        }
        return <p className="resume-paragraph" key={index}>{block.text}</p>;
      })}
    </article>
  );
}
