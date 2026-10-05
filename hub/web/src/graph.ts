export interface GraphNode { id:string; parents:string[]; message:string; author:string; time:string; lane:number; before:string[]; after:string[]; edges:{parent:string;fromLane:number;toLane:number}[];labels:{kind:string;name:string}[] }
export interface Graph {nodes:GraphNode[];truncated:boolean;lanes:number}
/** 使用 before／after lane 的 ID 對接相鄰列；交叉與合併不另猜 parent 排列。 */
export function segments(node: GraphNode, next?: GraphNode): [number,number,number,number][] {
  if(!next)return []
  const lines:[number,number,number,number][]=[]
  node.before.forEach((id,lane)=>{if(id!==node.id){const to=node.after.indexOf(id);if(to>=0)lines.push([lane,0,to,0.5])}})
  for(const edge of node.edges)if(edge.toLane>=0)lines.push([edge.fromLane,0,edge.toLane,0.5])
  node.after.forEach((id,lane)=>{const to=next.before.indexOf(id);if(to>=0)lines.push([lane,0.5,to,1])})
  return lines
}
