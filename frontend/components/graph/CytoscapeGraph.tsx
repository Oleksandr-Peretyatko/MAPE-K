'use client';

import { useEffect, useRef } from 'react';

interface Node {
  data: {
    id: string;
    label: string;
    type: string;
  };
}

interface Edge {
  data: {
    source: string;
    target: string;
  };
}

interface CytoscapeGraphProps {
  nodes: Node[];
  edges: Edge[];
  onNodeSelect: (nodeId: string) => void;
}

export default function CytoscapeGraph({
  nodes,
  edges,
  onNodeSelect,
}: CytoscapeGraphProps) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current) return;

    // Dynamically import cytoscape to avoid SSR issues
    import('cytoscape').then((cytoscapeModule) => {
      const cytoscape = cytoscapeModule.default;

      // Import layout
      import('cytoscape-dagre').then((dagreModule) => {
        const dagre = dagreModule.default;
        cytoscape.use(dagre);

        const cy = cytoscape({
          container: containerRef.current,
          elements: [...nodes, ...edges],
          style: [
            {
              selector: 'node',
              style: {
                content: 'data(label)',
                'background-color': '#3b82f6',
                'text-valign': 'center',
                'text-halign': 'center',
                'color': '#ffffff',
                'font-size': '12px',
                'padding': '8px',
                'border-width': '2px',
                'border-color': '#1e40af',
                width: 80,
                height: 80,
              },
            },
            {
              selector: 'node:selected',
              style: {
                'background-color': '#1e40af',
                'border-color': '#ffffff',
                'border-width': '3px',
              },
            },
            {
              selector: 'edge',
              style: {
                'line-color': '#d1d5db',
                'target-arrow-color': '#d1d5db',
                'target-arrow-shape': 'triangle',
                width: 2,
              },
            },
          ],
          layout: {
            name: 'dagre',
            directed: true,
            rankDir: 'LR',
            spacingFactor: 1.5,
          } as cytoscape.LayoutOptions,
        });

        cy.on('tap', 'node', (event) => {
          const nodeId = event.target.id();
          onNodeSelect(nodeId);
        });

        // Fit to container
        cy.fit();
      });
    });
  }, [nodes, edges, onNodeSelect]);

  return (
    <div
      ref={containerRef}
      className="w-full h-full bg-background-card"
      style={{ minHeight: '400px' }}
    />
  );
}
